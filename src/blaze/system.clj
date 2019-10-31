(ns blaze.system
  "Application System

  Call `init!` to initialize the system and `shutdown!` to bring it down.
  The specs at the beginning of the namespace describe the config which has to
  be given to `init!``. The server port has a default of `8080`."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.walk :refer [postwalk]]
    [blaze.bundle :as bundle]
    [blaze.datomic.transaction :as tx]
    [blaze.datomic.schema :as schema]
    [blaze.executors :as ex]
    [blaze.metrics :as metrics]
    [blaze.middleware.fhir.metrics :as fhir-metrics]
    [blaze.middleware.guard :as guard]
    [blaze.server :as server]
    [clojure.tools.reader.edn :as edn]
    [datomic.api :as d]
    [datomic-tools.schema :as dts]
    [integrant.core :as ig]
    [spec-coerce.alpha :refer [coerce]]
    [taoensso.timbre :as log])
  (:import
    [io.prometheus.client CollectorRegistry]
    [io.prometheus.client.hotspot StandardExports MemoryPoolsExports
                                  GarbageCollectorExports ThreadExports
                                  ClassLoadingExports VersionInfoExports]
    [java.time Clock]))



;; ---- Functions -------------------------------------------------------------

(defrecord Cfg [env-var spec default])


(defn- cfg [[env-var spec-form default]]
  (let [spec
        (if (symbol? spec-form)
          (var-get (resolve spec-form))
          spec-form)]
    (->Cfg env-var spec default)))


(defn- read-config []
  (try
    (edn/read-string
      {:readers {'blaze/ref ig/ref 'blaze/cfg cfg}}
      (slurp "blaze.edn"))
    (catch Exception _)))


(defn resolve-config [config env]
  (postwalk
    (fn [x]
      (if (instance? Cfg x)
        (coerce (:spec x) (get env (:env-var x) (:default x)))
        x))
    config))


(defn- load-namespaces [config]
  (let [loaded-ns (ig/load-namespaces config)]
    (log/info "Loaded the following namespaces:" (str/join ", " loaded-ns))))


(def ^:private base-config
  {:blaze/version "0.7.0-alpha3"

   :blaze/structure-definition
   {}

   :blaze.database/conn
   {:structure-definitions (ig/ref :blaze/structure-definition)
    :database/uri (->Cfg "DATABASE_URI" string? "datomic:mem://dev")}

   :blaze.handler/health
   {}

   :blaze/rest-api
   {:base-url (->Cfg "BASE_URL" string? "http://localhost:8080")
    :version (ig/ref :blaze/version)
    :database/conn (ig/ref :blaze.database/conn)
    :structure-definitions (ig/ref :blaze/structure-definition)
    :config #:blaze.rest-api{:context-path "fhir"}}

   :blaze.handler/app
   {:rest-api (ig/ref :blaze/rest-api)
    :health-handler (ig/ref :blaze.handler/health)}

   :blaze.server/executor
   {}

   :blaze/server
   {:port (->Cfg "SERVER_PORT" nat-int? 8080)
    :executor (ig/ref :blaze.server/executor)
    :handler (ig/ref :blaze.handler/app)
    :version (ig/ref :blaze/version)}

   :blaze.metrics/registry
   {}

   :blaze.handler/metrics
   {:registry (ig/ref :blaze.metrics/registry)}

   :blaze.metrics/server
   {:port (->Cfg "METRICS_SERVER_PORT" nat-int? 8081)
    :handler (ig/ref :blaze.handler/metrics)
    :version (ig/ref :blaze/version)}})


(s/fdef init!
  :args (s/cat :env any?))

(defn init!
  [{level "LOG_LEVEL" :or {level "info"} :as env}]
  (log/info "Set log level to:" (str/lower-case level))
  (log/merge-config! {:level (keyword (str/lower-case level))})
  (let [config (-> (merge-with merge base-config (read-config))
                   (resolve-config env))]
    (load-namespaces config)
    (ig/init config)))


(defn shutdown! [system]
  (ig/halt! system))



;; ---- Integrant Hooks -------------------------------------------------------

(defmethod ig/init-key :blaze/version
  [_ version]
  version)


(defn- upsert-schema [uri structure-definitions]
  (let [conn (d/connect uri)
        _ @(d/transact-async conn (dts/schema))
        {:keys [tx-data]} @(d/transact-async conn (schema/structure-definition-schemas structure-definitions))]
    (log/info "Upsert schema in database:" uri "creating" (count tx-data) "new facts")))


(defmethod ig/init-key :blaze.database/conn
  [_ {:database/keys [uri] :keys [structure-definitions]}]
  (if (d/create-database uri)
    (do
      (log/info "Created database at:" uri)
      (upsert-schema uri structure-definitions))
    (log/info "Use existing database at:" uri))

  (log/info "Connect with database:" uri)
  (d/connect uri))


(defmethod ig/init-key :guard
  [_ {:keys [authentication]}]
  (if (= authentication identity)
    identity
    (do (log/info "Enable authentication guard")
        guard/wrap-guard)))


(defmethod ig/init-key :transaction-interaction-executor
  [_ _]
  (ex/cpu-bound-pool "transaction-interaction-%d"))


(defmethod ig/init-key :blaze/clock
  [_ _]
  (Clock/systemDefaultZone))


#_(defmethod ig/init-key :fhir-capabilities-handler
  [_ {:keys [base-url version structure-definitions]}]
  (log/debug "Init FHIR capabilities interaction handler")
  (fhir-capabilities-handler/handler base-url version structure-definitions))


(defmethod ig/init-key :blaze.server/executor
  [_ _]
  (log/info "Init server executor")
  (ex/cpu-bound-pool "server-%d"))


(defmethod ig/init-key :blaze/server
  [_ {:keys [port executor handler version]}]
  (log/info "Start main server on port" port)
  (server/init! port executor handler version))


(defmethod ig/halt-key! :blaze/server
  [_ server]
  (log/info "Shutdown main server")
  (server/shutdown! server))


(defmethod ig/init-key :blaze.metrics/registry
  [_ {:keys [thread-pool-executors]}]
  (log/debug "Init metrics registry")
  (doto (CollectorRegistry. true)
    (.register (StandardExports.))
    (.register (MemoryPoolsExports.))
    (.register (GarbageCollectorExports.))
    (.register (ThreadExports.))
    (.register (ClassLoadingExports.))
    (.register (VersionInfoExports.))
    (.register fhir-metrics/requests-total)
    (.register fhir-metrics/request-duration-seconds)
    ;(.register json/parse-duration-seconds)
    ;(.register json/generate-duration-seconds)
    (.register tx/resource-upsert-duration-seconds)
    (.register tx/execution-duration-seconds)
    (.register tx/resources-total)
    (.register tx/datoms-total)
    (.register bundle/tx-data-duration-seconds)
    #_(.register ts/errors-total)
    #_(.register ts/request-duration-seconds)
    #_(.register evaluate-measure/compile-duration-seconds)
    #_(.register evaluate-measure/evaluate-duration-seconds)
    (.register
      (metrics/thread-pool-executor-collector
        (into [] thread-pool-executors)))))
(into [] {"a" 1})


(defmethod ig/init-key :blaze.metrics/server
  [_ {:keys [port handler version]}]
  (log/info "Start metrics server on port" port)
  (server/init! port (ex/single-thread-executor) handler version))


(defmethod ig/halt-key! :blaze.metrics/server
  [_ server]
  (log/info "Shutdown metrics server")
  (server/shutdown! server))
