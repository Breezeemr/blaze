(ns dromon.interaction.search-type
  "FHIR search interaction.

  https://www.hl7.org/fhir/http.html#search"
  (:require
   [dromon.handler.fhir.util :as fhir-util]
   [dromon.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
   [dromon.interaction.transforms :as transforms]
   [clojure.spec.alpha :as s]
   [clojure.set :as set]
   [datomic.api :as d]
   [integrant.core :as ig]
   [reitit.core :as reitit]
   [ring.middleware.params :refer [wrap-params]] [ring.util.response :as ring]
   [taoensso.timbre :as log])
  (:import
   [java.util UUID]))

(defn- match?
  [tree path search]
  (let [k       (first path)
        subtree (get tree k)]
    (cond
      (nil? k)              (= search tree)
      (nil? subtree)        false
      (sequential? subtree) (some (fn [st] (match? st (rest path) search))
                                  subtree)
      :else                 (match? subtree (rest path) search))))

(defn constraints->filter-fn
  [constraints]
  (fn [resource]
    (every? (fn [[path search]]
              (match? resource path search))
      (mapv (juxt :dromon.fhir.constraint/expression :dromon.fhir.constraint/value)
        constraints))))

(defn- entry
  [router {type "resourceType" id "id" :as resource}]
  {:fullUrl (fhir-util/instance-url router type id)
   :resource resource
   :search {:mode "match"}})

(defn- summary?
  "Returns true iff a summary result is requested."
  [{summary "_summary" :as query-params}]
  (or (zero? (fhir-util/page-size query-params)) (= "count" summary)))

(defn query-params->valid-search-params+value
  [config query-params]
  (->> query-params
    (map (fn [[k v]] (hash-map :dromon.fhir.SearchParameter/code k :dromon.fhir.SearchParameter/value v)))
    (set/join config)))

(defn search-param->constraint
  [{exp   :dromon.fhir.SearchParameter/expression
    order :dromon.fhir.constraint/order
    value :dromon.fhir.SearchParameter/value
    type  :dromon.fhir.SearchParameter/type
    :or   {order 0}}]
  {:dromon.fhir.constraint/expression exp
   :dromon.fhir.constraint/value      (case type
                                       "uuid" (UUID/fromString value)
                                       value)
   :dromon.fhir.constraint/operation  :matches
   :dromon.fhir.constraint/order      order})

(defn search
  [{:keys [database/conn
           dromon.fhir.SearchParameter/config
           schema/pattern
           schema/mapping
           query-params
           router]}]
  (let [db                                                          (d/db conn)
        [index & constraints]                                       (->> (query-params->valid-search-params+value config query-params)
                                                                      (map search-param->constraint)
                                                                      (sort-by :dromon.fhir.constraint/order))

        ;;TODO we need a more robust way to get the lookup-ref. e.g what if its not a lookup-ref just a value?
        {[attribute lookup-ref-attr] :dromon.fhir.constraint/expression
         lookup-ref-value            :dromon.fhir.constraint/value} (update index :dromon.fhir.constraint/expression transforms/->expression mapping)
        filter-fn                                                   (constraints->filter-fn constraints)]
    {:resourceType "Bundle"
     :type         "searchset"
     :entry        (into
                     []
                     (comp
                       (map :e)
                       (map #(d/pull db pattern %))
                       (map #(transforms/transform db mapping %))
                       (filter filter-fn)
                       (map #(dissoc % :db/id))
                       (take (fhir-util/page-size query-params))
                       (map #(set/rename-keys % {:fhir.Resource/id "id" :resourceType "resourceType"}))
                       (map #(update % "id" str))
                       (map #(entry router %)))
                     (d/datoms db :avet attribute [lookup-ref-attr lookup-ref-value]))}))

(defn- handler-intern [config]
  (fn [{{{:fhir.resource/keys [type]} :data} ::reitit/match
       :keys                                [params]
       ::reitit/keys                        [router]}]
    (log/info "Search:" type)

    ;;TODO it might be a good idea validate we have a valid index and constraints.
    ;; something simple like if (query-params->valid-search-params+value (:dromon.fhir.SearchParameter/config config) query-params)
    ;; is empty then we return something else here. e.g an error or empty set.
    (-> config
      (assoc :query-params params :router router)
      search
      ring/response)))

(s/def :handler.fhir/search fn?)

;;TODO improve spec
(s/fdef handler
  :args (s/keys :config map?)
  :ret :handler.fhir/search)

(defn handler
  [config]
  (-> config
    handler-intern
    wrap-params
    (wrap-observe-request-duration "search-type")))


(defmethod ig/init-key :dromon.interaction/search-type
  [[_ k] config]
  (log/info "Init FHIR search-type interaction handler for" k)
  (handler config))

(defmethod ig/init-key :dromon.fhir/SearchParameter
  [_ config]
  (log/info "Init search parameters")
  (if-let [params-fn (:fn config)]
    ((requiring-resolve params-fn))
    (:params config)))

;; TODO: these are more generic than just search_type
(defmethod ig/init-key :dromon.schema/mapping
  [[_ k] config]
  (log/info "Init schema mapping for" k)
  (if-let [mapping-fn (:fn config)]
    ((requiring-resolve mapping-fn))
    (merge (:mapping config) (:default config))))

(defmethod ig/init-key :dromon.schema/pattern
  [[_ k] config]
  (log/info "Init schema pull patterns for" k)
  (if-let [pull-fn (:fn config)]
    ((requiring-resolve pull-fn))
    (:pattern config)))
