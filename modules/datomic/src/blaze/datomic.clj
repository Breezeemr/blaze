(ns blaze.datomic
  (:require
    [blaze.datomic.schema :as schema]
    [blaze.datomic.transaction :as tx]
    [blaze.module :refer [reg-collector]]
    [datomic.api :as d]
    [datomic-tools.schema :as dts]
    [integrant.core :as ig]
    [taoensso.timbre :as log])
  (:import
    [java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor TimeUnit]))


(defn- upsert-schema [uri structure-definitions]
  (let [conn (d/connect uri)
        _ @(d/transact-async conn (dts/schema))
        {:keys [tx-data]} @(d/transact-async conn (schema/structure-definition-schemas structure-definitions))]
    (log/info "Upsert schema in database:" uri "creating" (count tx-data) "new facts")))


(defmethod ig/init-key :blaze.datomic/conn
  [_ {:database/keys [uri] :keys [structure-definitions]}]
  (if (d/create-database uri)
    (do
      (log/info "Created database at:" uri)
      (upsert-schema uri structure-definitions))
    (log/info "Use existing database at:" uri))

  (log/info "Connect with database:" uri)
  (d/connect uri))

(defmethod ig/init-key :consent-conn
  [_ config]
  (ig/init-key :blaze.datomic/conn config))


(defmethod ig/init-key ::tx/executor
  [_ _]
  (ThreadPoolExecutor. 20 20 1 TimeUnit/MINUTES (ArrayBlockingQueue. 100)))


(derive ::tx/executor :blaze.metrics/thread-pool-executor)


(reg-collector ::resource-upsert-duration-seconds
  tx/resource-upsert-duration-seconds)


(reg-collector ::execution-duration-seconds
  tx/execution-duration-seconds)


(reg-collector ::resources-total
  tx/resources-total)


(reg-collector ::datoms-total
  tx/datoms-total)
