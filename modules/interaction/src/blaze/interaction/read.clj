(ns blaze.interaction.read
  "FHIR read interaction.

  https://www.hl7.org/fhir/http.html#read"
  (:require
    [blaze.datomic.pull :as pull]
    [blaze.handler.util :as handler-util]
    [blaze.handler.fhir.util :as fhir-util]
    [blaze.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
    [blaze.fhir.transforms :as transforms]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [integrant.core :as ig]
    [manifold.deferred :as md]
    [reitit.core :as reitit]
    [ring.util.response :as ring]
    [taoensso.timbre :as log])
  (:import
    [java.time ZonedDateTime ZoneId]
    [java.time.format DateTimeFormatter]
    [java.util UUID]))


(def ^:private gmt (ZoneId/of "GMT"))


(defn- last-modified [resource]
  (->> (ZonedDateTime/ofInstant (:last-transaction-instant (meta resource)) gmt)
       (.format DateTimeFormatter/RFC_1123_DATE_TIME)))


(defn- etag [resource]
  (str "W/\"" (:version-id (meta resource)) "\""))


(defn- db [conn vid]
  (cond
    (and vid (re-matches #"\d+" vid))
    (let [vid (Long/parseLong vid)]
      (-> (d/sync conn vid) (md/chain #(d/as-of % vid))))

    vid
    (md/error-deferred
      {::anom/category ::anom/not-found
       :fhir/issue "not-found"})

    :else
    (d/db conn)))


(defn pull-resource [db pattern mapping id]
  (->> (d/pull db pattern [:fhir.Resource/id id])
       (transforms/transform mapping)))


(defn- handler-intern [{:keys [database/conn schema/pattern schema/mapping]}]
  (fn [{{{:fhir.resource/keys [type]} :data} ::reitit/match
        {:keys [id vid]} :path-params}]
    (prn "Read:" type id)
    (-> (db conn vid)
        (md/chain'
          (fn [db]
            (if-let [resource (pull-resource db pattern mapping (UUID/fromString id))]
              (if (:deleted (meta resource))
                (-> (handler-util/operation-outcome
                     {:fhir/issue "deleted"})
                    (ring/response)
                    (ring/status 410)
                    ;; (ring/header "Last-Modified" (last-modified resource))
                    (ring/header "ETag" (etag resource)))
                (-> (ring/response resource)
                    ;; (ring/header "Last-Modified" (last-modified resource))
                    (ring/header "ETag" (etag resource))))
              (handler-util/error-response
                {::anom/category ::anom/not-found
                 :fhir/issue "not-found"}))))
        (md/catch' handler-util/error-response))))


(defn wrap-interaction-name [handler]
  (fn [{{:keys [vid]} :path-params :as request}]
    (-> (handler request)
        (md/chain'
          (fn [response]
            (assoc response :fhir/interaction-name (if vid "vread" "read")))))))


(s/def :handler.fhir/read fn?)


(s/fdef handler
  :args (s/cat :conn ::ds/conn)
  :ret :handler.fhir/read)

(defn handler
  ""
  [config]
  (-> (handler-intern config)
      (wrap-interaction-name)
      (wrap-observe-request-duration)))


(defmethod ig/init-key :blaze.interaction/read
  [[_ k] config]
  (log/info "Init FHIR read interaction handler for" k)
  (handler config))
