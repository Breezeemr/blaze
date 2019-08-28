(ns blaze.fhir.operation.evaluate-measure.handler.impl
  (:require
    [blaze.datomic.util :as datomic-util]
    [blaze.executors :as ex]
    [blaze.fhir.operation.evaluate-measure.measure :refer [evaluate-measure]]
    [blaze.fhir.response.create :as response]
    [blaze.handler.fhir.util :as fhir-util]
    [blaze.handler.util :as handler-util]
    [blaze.terminology-service :refer [term-service?]]
    [blaze.util :refer [anom-let]]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [manifold.deferred :as md]
    [reitit.core :as reitit]
    [ring.util.response :as ring])
  (:import
    [java.time Clock OffsetDateTime]))


(defn- now [clock]
  (OffsetDateTime/now ^Clock clock))


(defn- handle
  [clock conn term-service db executor
   {::reitit/keys [router] :keys [request-method headers]
    {:strs [periodStart periodEnd]} :query-params}
   measure]
  (let [period [periodStart periodEnd]]
    (-> (md/future-with executor
          (evaluate-measure (now clock) db period measure))
        (md/chain'
          (fn [result]
            (if (::anom/category result)
              (handler-util/error-response result)
              (cond
                (= :get request-method)
                (ring/response result)

                (= :post request-method)
                (let [id (str (d/squuid))
                      return-preference (handler-util/preference headers "return")]
                  (-> (fhir-util/upsert-resource
                        conn term-service db :server-assigned-id (assoc result "id" id))
                      (md/chain'
                        #(response/build-created-response
                           router return-preference (:db-after %) "MeasureReport" id))
                      (md/catch' handler-util/error-response))))))))))


(s/fdef handler
  :args (s/cat :clock #(instance? Clock %)
               :conn ::ds/conn
               :term-service term-service?
               :executor ex/executor?))

(defn handler [clock conn term-service executor]
  (fn [{{:keys [id]} :path-params :as request}]
    (let [db (d/db conn)]
      (if-let [measure (datomic-util/resource db "Measure" id)]
        (if (datomic-util/deleted? measure)
          (-> (handler-util/operation-outcome
                {:fhir/issue "deleted"})
              (ring/response)
              (ring/status 410))
          (handle clock conn term-service db executor request measure))
        (handler-util/error-response
          {::anom/category ::anom/not-found
           :fhir/issue "not-found"})))))
