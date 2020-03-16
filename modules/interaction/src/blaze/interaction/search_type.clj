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

(defn- resource-pred [query-params config]
  (let [valid-query-params  (select-keys query-params (map :blaze.fhir.SearchParameter/code config))
        select-params-by-code (fn [config code]
                              (->> config
                                   (filter #(= (:blaze.fhir.SearchParameter/code %) code))
                                   first))]
    (when (seq valid-query-params)
      (fn [resource]
        (every? (fn [[path search]]
                  ;; (clojure.pprint/pprint resource)
                  ;; (prn search path (get-in resource path))
                  (match? resource path search))
                (mapv (fn [[k v]]
                        (let [params (select-params-by-code config k)
                              path   (:blaze.fhir.SearchParameter/expression params)
                              type   (:blaze.fhir.SearchParameter/type params)
                              search (case type
                                       "uuid" (UUID/fromString v)
                                       v)]
                          [path search]))
                      valid-query-params))))))

(defn- entry
  [router {type "resourceType" id "id" :as resource}]
  {:fullUrl (fhir-util/instance-url router type id)
   :resource resource
   :search {:mode "match"}})


(defn- summary?
  "Returns true iff a summary result is requested."
  [{summary "_summary" :as query-params}]
  (or (zero? (fhir-util/page-size query-params)) (= "count" summary)))


(defn- search [router db type query-params config pattern mapping]
  (let [pred (resource-pred query-params config)
        type (if-let [new-type (:type mapping)]
               new-type
               type)]
    (cond->
        {:resourceType "Bundle"
         :type "searchset"}

      (nil? pred)
      (assoc :total (or (d/q '[:find (count ?e) .
                               :in $ ?type
                               :where
                               [?e :phi.element/type ?type]
                               [?e :fhir.Resource/id]]
                             db
                             (str "fhir-type/" type))
                        0))

      (not (summary? query-params))
      (assoc
       :entry
       (into
        []
        (comp
         (map :e)
         (map #(d/pull db pattern %))
         ;; (map #(doto % clojure.pprint/pprint))
         (filter #(not (:deleted (meta %))))
         (filter (or pred (fn [_] true)))
         #_(filter (fn [resource]
                   (if (some? (:fhir.Resource/id resource))
                     true
                     (do
                       (log/info (str "Following " type " entity does not have :fhir.Resource/id: " (:db/id resource)))
                       false))))
         (map #(dissoc % :db/id))
         (take (fhir-util/page-size query-params))
         (map #(transforms/transform db mapping %))
         (map #(rename-keys % {:fhir.Resource/id "id" :resourceType "resourceType"}))
         (map #(update % "id" str))
         (map #(entry router %)))
        (d/datoms db :avet :phi.element/type (str "fhir-type/" type)))))))

(comment

  ;; setup some state to track data we get live
  (def d (atom nil))

  ;;TODO this function will need to dynamic create and order contraints
  (defn search-v2 [router db type query-params config pattern mapping]
    #_(reset! d [router db type query-params config pattern mapping])
    (let [
          pred (resource-pred query-params config)
          type (if-let [new-type (:type mapping)]
                 new-type
                 type)]
      (cond->
          {:resourceType "Bundle"
           :type "searchset"}

        (nil? pred)
        (assoc :total (or (d/q '[:find (count ?e) .
                                 :in $ ?type
                                 :where
                                 [?e :phi.element/type ?type]
                                 [?e :fhir.Resource/id]]
                            db
                            (str "fhir-type/" type))
                        0))

        (not (summary? query-params))
        (assoc
          :entry
          (into
            []
            (comp
              (map :e)
              (map #(d/pull db pattern %))
              ;; (map #(doto % clojure.pprint/pprint))
              (filter #(not (:deleted (meta %))))
              (filter (or pred (fn [_] true)))
              #_(filter (fn [resource]
                          (if (some? (:fhir.Resource/id resource))
                            true
                            (do
                              (log/info (str "Following " type " entity does not have :fhir.Resource/id: " (:db/id resource)))
                              false))))
              (map #(dissoc % :db/id))
              (take (fhir-util/page-size query-params))
              (map #(transforms/transform db mapping %))
              (map #(rename-keys % {:fhir.Resource/id "id" :resourceType "resourceType"}))
              (map #(update % "id" str))
              (map #(entry router %)))

            ;;TODO replace with a dynamic way to pick this constraint as the first one
            (d/datoms db :avet :fhir.v3.Condition/subject [:fhir.Resource/id (java.util.UUID/fromString (get query-params "patient"))]))))))


  ;;Helper function to get valid params
  (defn get-valid-query-params
    [config query-params]
    (
     reduce-kv
     (fn [c query value]
       (conj c (assoc (first (filter #(= (:blaze.fhir.SearchParameter/code %) query) config)) :value value)))
     []
     query-params))

  ;; to get the value we would need to pass the database
  (defn ->value
    [{:keys [blaze.fhir.SearchParameter/type value]}]
    (case type
      "uuid" (UUID/fromString value)))

  ;; To turn our queries into a fact
  ;;TODO assumes a lot
 
  (defn q->fact
    [{[a b] :blaze.fhir.SearchParameter/expression
      order :blaze.fhir.constraint/order
      :as exp
      :or {order 0}}]
    {:blaze.fhir.constraint/fact [a [b (->value exp)]]
     :blaze.fhir.constraint/order order})

  ;;bringing it together we produce a list of almost datalog like facts like this. were just currently assigning the type an order of 5 this is rather explict but
  ;; its the only way.

  ;; 

  (let [[router db type query-params config pattern mapping] @d]
    (->> (get-valid-query-params config query-params)
      (map #(q->fact %))
      (cons {:blaze.fhir.constraint/fact  [:phi.element/type type]
             :blaze.fhir.constraint/order 5})
      (sort-by :blaze.fhir.constraint/order)
      )
    )

  ;; => ({:fact [:fhir.v3.Condition/subject [:fhir.Resource/id #uuid "55776ed1-2072-4d0c-b19f-a2d725aadf15"]], :blaze.fhir.constraint/order 0} #:blaze.fhir.constraint{:fact [:phi.element/type "Condition"], :order 5})


  )

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
