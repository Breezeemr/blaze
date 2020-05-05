(ns dromon.datomic
  (:require [datomic-tools.schema :as dts]
            [datomic.api :as d]
            [dromon.datomic.schema :as schema]
            [dromon.datomic.transaction :as tx]
            [dromon.module :refer [reg-collector]]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor TimeUnit]))

(defn- upsert-schema [uri structure-definitions search-parameters]
  (let [conn (d/connect uri)
        _ @(d/transact-async conn (dts/schema))
        tx-data (into (schema/structure-definition-schemas structure-definitions)
                      (schema/search-parameter-schemas search-parameters))
        {:keys [tx-data]} @(d/transact-async conn tx-data)]
    (log/info "Upsert schema in database:" uri "creating" (count tx-data) "new facts")))


(defmethod ig/init-key :dromon.datomic/conn
  [_ {:database/keys [uri] :keys [structure-definitions search-parameters]}]
  (if (d/create-database uri)
    (do
      (log/info "Created database at:" uri)
      (upsert-schema uri structure-definitions search-parameters))
    (log/info "Use existing database at:" uri))

  (log/info "Connect with database:" uri)
  (d/connect uri))


(defmethod ig/init-key ::tx/executor
  [_ _]
  (ThreadPoolExecutor. 20 20 1 TimeUnit/MINUTES (ArrayBlockingQueue. 100)))


(derive ::tx/executor :dromon.metrics/thread-pool-executor)


(reg-collector ::resource-upsert-duration-seconds
  tx/resource-upsert-duration-seconds)


(reg-collector ::execution-duration-seconds
  tx/execution-duration-seconds)


(reg-collector ::resources-total
  tx/resources-total)


(reg-collector ::datoms-total
  tx/datoms-total)
