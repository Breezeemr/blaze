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

(defn coding->code
  [coding]
  (-> coding
      :Coding/code
      :code/code))

(defn codeable-concept->coding
  [codeable-concept]
  (map coding->code
       (:CodeableConcept/coding codeable-concept)))

(defn match-codeable-concept?
  [search codeable-concept]
  (contains? (set (codeable-concept->coding codeable-concept))
             search))

;;NOTE function assumes reference is always a patient.
(defn match-reference?
  [search reference]
  (= (:Patient/id reference)
     search))


(defn match-identifier?
  [search identifier]
  (= (:Identifier/value identifier)
     search))

(defn provision->actor-ids
  [provision]
  (map
    (fn [m] (-> m :Consent.provision.actor/reference :Patient/id))
    (:Consent.provision/actor provision)))

(defn match-actor?
  [search provision]
  (contains? (set (provision->actor-ids provision))
             search))

;;TODO add identifier search back in.

(def match-key->match-fn
  {:match-reference?        match-reference?
   :match-actor?            match-actor?
   :match-codeable-concept? match-codeable-concept?})


(defn match?
  [state path search]
  (cond
    (= search state) true
    (vector? state)  (some (fn [s] (match? s path search)) state)
    :else            (when (seq path)
                       (recur ((first path) state)
                              (rest path)
                              search))))

#_(defn- resource-pred
    [db query-params {:blaze.fhir.searchParameter/keys [code expression]}]
    (let [search-info (reduce-kv
                        (fn [coll search-param search-value]
                          (conj coll
                                {:search-param search-param
                                 :search-value search-value
                                 :matches-fn   matches-fn
                                 :attr         attr
                                 :cardinality  (:db/cardinality (util/cached-entity db attr))}))
                        []
                        query-params)]
      ;; NOTE this is removing invalid query but not rejecting the query
      ;; if it has invalid params
      (when (seq search-info)
        (fn [resource]
          (every? true? (reduce
                          (fn [matches {:keys [matches-fn attr search-value cardinality]}]
                            (let [attr-instance (get resource attr)]
                              (conj matches
                                    (if (= :db.cardinality/many cardinality)
                                      (some (partial matches-fn search-value) attr-instance)
                                      (matches-fn search-value attr-instance)))))
                          []
                          search-info))))))


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

