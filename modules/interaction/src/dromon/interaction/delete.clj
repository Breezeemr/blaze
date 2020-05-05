(ns dromon.interaction.delete
  "FHIR delete interaction.

  https://www.hl7.org/fhir/http.html#delete"
  (:require [clojure.spec.alpha :as s]
            [cognitect.anomalies :as anom]
            [datomic-spec.core :as ds]
            [datomic.api :as d]
            [dromon.datomic.util :as util]
            [dromon.executors :refer [executor?]]
            [dromon.handler.fhir.util :as handler-fhir-util]
            [dromon.handler.util :as handler-util]
            [dromon.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
            [integrant.core :as ig]
            [manifold.deferred :as md]
            [reitit.core :as reitit]
            [ring.util.response :as ring]
            [ring.util.time :as ring-time]
            [taoensso.timbre :as log]))

(defn- handler-intern [transaction-executor conn]
  (fn [{{{:fhir.resource/keys [type]} :data} ::reitit/match
        {:keys [id]} :path-params}]
    (let [db (d/db conn)]
      (if (util/resource db type id)
        (-> (handler-fhir-util/delete-resource
              transaction-executor
              conn
              db
              type
              id)
            (md/chain'
              (fn [{db :db-after}]
                (let [last-modified (:db/txInstant (util/basis-transaction db))]
                  (-> (ring/response nil)
                      (ring/status 204)
                      (ring/header "Last-Modified" (ring-time/format-date last-modified))
                      (ring/header "ETag" (str "W/\"" (d/basis-t db) "\"")))))))
        (handler-util/error-response
          {::anom/category ::anom/not-found
           :fhir/issue "not-found"})))))


(s/def :handler.fhir/delete fn?)


(s/fdef handler
  :args (s/cat :transaction-executor executor? :conn ::ds/conn)
  :ret :handler.fhir/delete)

(defn handler
  ""
  [transaction-executor conn]
  (-> (handler-intern transaction-executor conn)
      (wrap-observe-request-duration "delete")))


(defmethod ig/init-key :dromon.interaction/delete
  [_ {:database/keys [transaction-executor conn]}]
  (log/info "Init FHIR delete interaction handler")
  (handler transaction-executor conn))
