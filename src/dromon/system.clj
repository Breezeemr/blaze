(ns dromon.system
  "Application System

  Call `init!` to initialize the system and `shutdown!` to bring it down.
  The specs at the beginning of the namespace describe the config which has to
  be given to `init!``. The server port has a default of `8080`."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.reader.edn :as edn]
            [clojure.walk :refer [postwalk]]
            [dromon.executors :as ex]
            [dromon.server :as server]
            [integrant.core :as ig]
            [spec-coerce.alpha :refer [coerce]]
            [taoensso.timbre :as log]
            [aleph.netty :as netty])
  (:import java.io.PushbackReader
           java.time.Clock))

;; ---- Functions -------------------------------------------------------------

(defrecord Cfg [env-var spec default])


(defn- cfg
  "Creates a config entry which consists of the name of a environment variable,
  a spec and a default value.

  Config entries appear in dromon.edn files."
  [[env-var spec-form default]]
  (let [spec
        (if (symbol? spec-form)
          (var-get (resolve spec-form))
          spec-form)]
    (->Cfg env-var spec default)))


(defn- read-dromon-edn []
  (log/info "Try to read dromon.edn ...")
  (try
    (with-open [rdr (PushbackReader. (io/reader (io/resource "dromon.edn")))]
      (edn/read
        {:readers {'dromon/ref ig/ref
                   'dromon/cfg cfg
                   'dromon/refset ig/refset
                   }}
        rdr))
    (catch Exception e
      (log/warn "Problem while reading dromon.edn. Skipping it." e))))


(defn- get-blank [m k default]
  (let [v (get m k)]
    (if (or (nil? v) (str/blank? v))
      default
      v)))


(defn resolve-config
  "Resolves config entries to there actual values with the help of an
  environment."
  [config env]
  (postwalk
    (fn [x]
      (if (instance? Cfg x)
        (when-let [value (get-blank env (:env-var x) (:default x))]
          (coerce (:spec x) value))
        x))
    config))


(defn- load-namespaces [config]
  (log/info "Loading namespaces ...")
  (let [loaded-ns (ig/load-namespaces config)]
    (log/info "Loaded the following namespaces:" (str/join ", " loaded-ns))))


(def ^:private root-config
  {:dromon/version "0.8.0-alpha.9"

   :dromon/clock {}

   :dromon/structure-definition {}

   :dromon/search-parameter {}

   :dromon.datomic.transaction/executor {}

   :dromon.datomic/conn
   {:structure-definitions (ig/ref :dromon/structure-definition)
    :search-parameters (ig/ref :dromon/search-parameter)
    :database/uri (->Cfg "DATABASE_URI" string? "datomic:mem://dev")}

   :dromon.datomic/resource-upsert-duration-seconds {}
   :dromon.datomic/execution-duration-seconds {}
   :dromon.datomic/resources-total {}
   :dromon.datomic/datoms-total {}

   :dromon.handler/health {}

   :dromon/rest-api
   {:base-url (->Cfg "BASE_URL" string? "http://localhost:8080")
    :version (ig/ref :dromon/version)
    :structure-definitions (ig/ref :dromon/structure-definition)
    :auth-backends (ig/refset :dromon.auth/backend)
    :context-path "/fhir"}

   :dromon.rest-api/requests-total {}
   :dromon.rest-api/request-duration-seconds {}
   :dromon.rest-api/parse-duration-seconds {}
   :dromon.rest-api/generate-duration-seconds {}
   :dromon.rest-api/tx-data-duration-seconds {}

   :dromon.handler/app
   {:rest-api (ig/ref :dromon/rest-api)
    :health-handler (ig/ref :dromon.handler/health)}

   :dromon.server/executor {}

   :dromon.server/ssl-context {}

   :dromon/server
   {:port (->Cfg "SERVER_PORT" nat-int? 8080)
    :executor (ig/ref :dromon.server/executor)
    :handler (ig/ref :dromon.handler/app)
    :version (ig/ref :dromon/version)
    :ssl-context (ig/ref :dromon.server/ssl-context)}

   :dromon/thread-pool-executor-collector
   {:executors (ig/refmap :dromon.metrics/thread-pool-executor)}

   :dromon.metrics/registry
   {:collectors (ig/refset :dromon.metrics/collector)}

   :dromon.handler/metrics
   {:registry (ig/ref :dromon.metrics/registry)}

   :dromon.metrics/server
   {:port (->Cfg "METRICS_SERVER_PORT" nat-int? 8081)
    :handler (ig/ref :dromon.handler/metrics)
    :version (ig/ref :dromon/version)}})


(defn- feature-enabled?
  {:arglists '([env feature])}
  [env {:keys [toggle]}]
  (let [value (get env toggle)]
    (and (not (str/blank? value)) (not= "false" (some-> value str/trim)))))


(defn- merge-features
  {:arglists '([dromon-edn env])}
  [{:keys [base-config features]} env]
  (reduce
    (fn [res {:keys [name config] :as feature}]
      (let [enabled? (feature-enabled? env feature)]
        (log/info "Feature" name (if enabled? "enabled" "disabled"))
        (if enabled?
          (merge res config)
          res)))
    base-config
    features))


(s/fdef init!
  :args (s/cat :env any?))

(defn init!
  [{level "LOG_LEVEL" :or {level "info"} :as env}]
  (log/info "Set log level to:" (str/lower-case level))
  (log/merge-config! {:level (keyword (str/lower-case level))})
  (let [config (merge-features (read-dromon-edn) env)
        config (-> (merge-with merge root-config config)
                   (resolve-config env))]
    (load-namespaces config)
    (-> config ig/prep ig/init)))


(defn shutdown! [system]
  (ig/halt! system))



;; ---- Integrant Hooks -------------------------------------------------------

(defmethod ig/init-key :dromon/version
  [_ version]
  version)


(defmethod ig/init-key :dromon/clock
  [_ _]
  (Clock/systemDefaultZone))


#_(defmethod ig/init-key :fhir-capabilities-handler
    [_ {:keys [base-url version structure-definitions]}]
    (log/debug "Init FHIR capabilities interaction handler")
    (fhir-capabilities-handler/handler base-url version structure-definitions))


(defmethod ig/init-key :dromon.server/executor
  [_ _]
  (log/info "Init server executor")
  (ex/cpu-bound-pool "server-%d"))


(derive :dromon.server/executor :dromon.metrics/thread-pool-executor)

(defmethod ig/init-key :dromon.server/ssl-context
  [_ {:keys [ssl/option]}]
  (case option
    :ssl/self-signed (netty/self-signed-ssl-context)))

(defmethod ig/init-key :dromon/server
  [_ {:keys [port] :as config}]
  (log/info "Start main server on port" port)
  (server/init! config))

(defmethod ig/halt-key! :dromon/server
  [_ server]
  (log/info "Shutdown main server")
  (server/shutdown! server))


(defmethod ig/init-key :dromon.metrics/server
  [_ {:keys [port] :as config}]
  (log/info "Start metrics server on port" port)
  (server/init! (assoc config :executor (ex/single-thread-executor))))

(defmethod ig/halt-key! :dromon.metrics/server
  [_ server]
  (log/info "Shutdown metrics server")
  (server/shutdown! server))
