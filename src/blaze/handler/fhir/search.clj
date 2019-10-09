(ns blaze.handler.fhir.search
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
   [reitit.core :as reitit]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.response :as ring]))

(defn coding->code
  [coding]
  (-> coding :Coding/code :code/code))

(defn codeable-concept->coding
  [codeable-concept]
  (map coding->code (:CodeableConcept/coding codeable-concept)))

(defn match-codeable-concept
  [search codeable-concept]
  ((set (codeable-concept->coding codeable-concept)) search))

(def res-type+attr->matches-fn
  {"Condition" {"identity" "TODO"
                "subject"  (fn [search resource] (= (:Patient/id resource)) search)
                "category" match-codeable-concept}})

(def get-valid-search-params #{"identifier" "subject" "category"})

(def valid-search-params
  (reduce-kv
    (fn [valid? _ search-param]
      (into valid? (keys search-param)))
    #{}
    res-type+attr->matches-fn))

(defn get-resource-pred
  [db type query-params]
  (if-some [valid-search-params (select-keys query-params get-valid-search-params)]
    (let [search-info (reduce-kv
                        (fn [coll search-param search-value]
                          (let [attr (keyword type search-param)]
                            (conj coll
                                  {:search-param search-param
                                   :search-value search-value
                                   :matches-fn   (get-in res-type+attr->matches-fn [type search-param])
                                   :attr         attr
                                   :cardinality  (:db/cardinality (util/cached-entity db attr))})))
                        []
                        valid-search-params)]
      (fn [resource]
        (every? some? (reduce
                        (fn [matches {:keys [matches-fn attr search-value cardinality]}]
                          (let [r (get resource attr)]
                            (conj matches
                                  (if (= :db.cardinality/many cardinality)
                                    (some (partial matches-fn search-value) r)
                                    (matches-fn search-value r)))))
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

(defn- search [router db type query-params]
  (let [pred (get-resource-pred db type query-params)]
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


(defn- handler-intern [conn]
  (fn [{{:keys [type]} :path-params :keys [params] ::reitit/keys [router]}]
    (-> (search router (d/db conn) type params)
        (ring/response))))


(s/def :handler.fhir/search fn?)


(s/fdef handler
  :args (s/cat :conn ::ds/conn)
  :ret :handler.fhir/search)

(defn handler
  ""
  [conn]
  (-> (handler-intern conn)
      (wrap-params)
      (wrap-observe-request-duration "search-type")))
