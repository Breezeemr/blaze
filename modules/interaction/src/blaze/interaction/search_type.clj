(ns blaze.interaction.search-type
  "FHIR search interaction.

  https://www.hl7.org/fhir/http.html#search"
  (:require
   [blaze.datomic.pull :as pull]
   [blaze.datomic.util :as util]
   [blaze.handler.fhir.util :as fhir-util]
   [blaze.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
   [clojure.spec.alpha :as s]
   [datomic.api :as d]
   [datomic-spec.core :as ds]
   [integrant.core :as ig]
   [reitit.core :as reitit]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.response :as ring]
   [taoensso.timbre :as log]))

(defn match?
  [state path search]
  (cond
    (= search state) true
    (vector? state)  (some (fn [s] (match? s path search)) state)
    :else            (when (seq path)
                       (recur ((first path) state)
                              (rest path)
                              search))))

(defn- entry
  [router {type "resourceType" id "id" :as resource}]
  {:fullUrl  (fhir-util/instance-url router type id)
   :resource resource
   :search   {:mode "match"}})


(defn- summary?
  "Returns true iff a summary result is requested."
  [{summary "_summary" :as query-params}]
  (or (zero? (fhir-util/page-size query-params)) (= "count" summary)))

(defn- search [router db type query-params config]
  (let [valid-query-params  (select-keys query-params (map :blaze.fhir.SearchParameter/code config))
        select-path-by-code (fn [config code]
                              (->> config
                                   (filter #(= (:blaze.fhir.SearchParameter/code %) code))
                                   first
                                   :blaze.fhir.SearchParameter/expression))
        pred                (when (seq valid-query-params)
                              (fn [resource] (every? (fn [[path search]] (match? resource path search))
                                                    (mapv (fn [[k v]] [(select-path-by-code config k) v]) valid-query-params))))]
    (cond->
        {:resourceType "Bundle"
         :type "searchset"}

      (nil? pred)
      (assoc :total (util/type-total db type))

      (not (summary? query-params))
      (assoc
        :entry
        (into
          []
          (comp
            (map #(d/entity db (:e %)))
            (filter (or pred (fn [_] true)))
            (map #(pull/pull-resource* db type %))
            (filter #(not (:deleted (meta %))))
            (take (fhir-util/page-size query-params))
            (map #(entry router %)))
          (d/datoms db :aevt (util/resource-id-attr type)))))))

(defn- handler-intern [{:keys [database/conn blaze.fhir.SearchParameter/config]}]
  (fn [{:keys         [params uri]
       ::reitit/keys [router]}]
    ;;NOTE previously the "type" was a templated (e.g <type>/<id>)
    ;;It's not clear that will be the way to do it moving forward.
    ;;The solution here might not be robust enough either, as the uri
    ;;might contain _more_ then the type
    (-> (search router (d/db conn) uri params config)
        (ring/response))))

(s/def :handler.fhir/search fn?)

;;TODO fix this spec
;; (s/fdef handler
;;   :args (s/cat :conn ::ds/conn)
;;   :ret :handler.fhir/search)

(s/fdef handler
  :args any?
  :ret :handler.fhir/search)

(defn handler
  ""
  [config]
  (-> (handler-intern config)
      (wrap-params)
      (wrap-observe-request-duration "search-type")))

(defmethod ig/init-key :blaze.interaction/search-type
  [_ config]
  (log/info "Init FHIR search-type interaction handler")
  (handler config))
