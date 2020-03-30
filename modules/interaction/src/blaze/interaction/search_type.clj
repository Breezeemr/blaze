(ns blaze.interaction.search-type
  "FHIR search interaction.

  https://www.hl7.org/fhir/http.html#search"
  (:require
    [blaze.datomic.pull :as pull]
    [blaze.datomic.util :as db]
    [blaze.handler.fhir.util :as fhir-util]
    [blaze.handler.util :as handler-util]
    [blaze.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
    [blaze.fhir.transforms :as transforms]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.set :refer [rename-keys]]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [integrant.core :as ig]
    [reitit.core :as reitit]
    [ring.middleware.params :refer [wrap-params]] [ring.util.response :as ring]
    [taoensso.timbre :as log]
    [clojure.set :as set])
  (:import
   [java.util UUID]))

(defn- match?
  [tree {:blaze.fhir.constraint/keys [expression value operation modifier] :as c}]
  (let [k       (first expression)
        n (assoc c :blaze.fhir.constraint/expression (rest expression))
        subtree (get tree k)]
    (cond
      (nil? k)              (modifier (operation value tree))
      (nil? subtree)        false
      (sequential? subtree) (some (fn [st] (match? st n)) subtree)
      :else                 (match? subtree n))))

(defn constraints->filter-fn
  [constraints]
  (fn [resource]
    (every? (fn [constraint] (match? resource constraint))
      constraints)))

(defn- entry
  [router {type "resourceType" id "id" :as resource}]
  {:fullUrl (fhir-util/instance-url router type id)
   :resource resource
   :search {:mode "match"}})

(defn- summary?
  "Returns true iff a summary result is requested."
  [{summary "_summary" :as query-params}]
  (or (zero? (fhir-util/page-size query-params)) (= "count" summary)))

(defn query-params->search-params
  [query-params]
  (reduce-kv
    (fn [c k v] (let  [[k modifier] (str/split k #":")]
                 (conj c
                   (cond-> {:blaze.fhir.SearchParameter/code k
                            :blaze.fhir.SearchParameter/value v}
                     modifier (assoc :blaze.fhir.constraint/modifier ({"not" not} modifier))))))
    []
    query-params))

;;TODO rethink this. changing the key name isnt useful
(defn search-param->constraint
  [{exp       :blaze.fhir.SearchParameter/expression
    order     :blaze.fhir.constraint/order
    operation :blaze.fhir.constraint/operation
    value     :blaze.fhir.SearchParameter/value
    type      :blaze.fhir.SearchParameter/type
    :or       {order 0 operation =}}]
  {:blaze.fhir.constraint/expression exp
   :blaze.fhir.constraint/value      (case type
                                       "uuid" (UUID/fromString value)
                                       value)
   :blaze.fhir.constraint/operation  operation
   :blaze.fhir.constraint/order      order})

(defn search [router db type query-params config pattern mapping]
  (let [[index & constraints]                                      (->> query-params
                                                                     query-params->search-params
                                                                     (set/join config)
                                                                     (map search-param->constraint)
                                                                     (sort-by :blaze.fhir.constraint/order))
        {[attribute lookup-ref-attr] :blaze.fhir.constraint/expression
         ;;TODO we need a more robust way to get the lookup-ref. e.g what if its not a lookup-ref just a value?
         lookup-ref-value            :blaze.fhir.constraint/value} (update index :blaze.fhir.constraint/expression transforms/->expression mapping)
        filter-fn (constraints->filter-fn constraints)]
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
                       (map #(rename-keys % {:fhir.Resource/id "id" :resourceType "resourceType"}))
                       (map #(update % "id" str))
                       (map #(entry router %)))
                     (d/datoms db :avet attribute [lookup-ref-attr lookup-ref-value]))}))

(defn- handler-intern [{:keys [database/conn  blaze.fhir.SearchParameter/config schema/pattern schema/mapping]}]
  (fn [{{{:fhir.resource/keys [type]} :data} ::reitit/match
       :keys [params]
       ::reitit/keys [router]}]
    (log/info "Search:" type)
    (-> (search router (d/db conn) type params config pattern mapping)
        (ring/response))))


(s/def :handler.fhir/search fn?)

;;TODO improve spec
(s/fdef handler
  :args (s/keys :config map?)
  :ret :handler.fhir/search)

(defn handler
  ""
  [config]
  (-> (handler-intern config)
      (wrap-params)
      (wrap-observe-request-duration "search-type")))


(defmethod ig/init-key :blaze.interaction/search-type
  [[_ k] config]
  (log/info "Init FHIR search-type interaction handler for" k)
  (handler config))


(defmethod ig/init-key :blaze.fhir/SearchParameter
  [_ config]
  (log/info "Init search parameters")
  (if-let [params-fn (:fn config)]
    ((requiring-resolve params-fn))
    (:params config)))


;; TODO: these are more generic than just search_type

(defmethod ig/init-key :blaze.schema/mapping
  [[_ k] config]
  (log/info "Init schema mapping for" k)
  (if-let [mapping-fn (:fn config)]
    ((requiring-resolve mapping-fn))
    (merge (:mapping config) (:default config))))


(defmethod ig/init-key :blaze.schema/pattern
  [[_ k] config]
  (log/info "Init schema pull patterns for" k)
  (if-let [pull-fn (:fn config)]
    ((requiring-resolve pull-fn))
    (:pattern config)))
