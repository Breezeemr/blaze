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


(defn- match?
  [tree path search]
  (let [k       (first path)
        subtree (get tree k)]
    (cond
      (nil? k)       (= search tree)
      (nil? subtree) false
      (set? subtree) (some (fn [st] (match? st (rest path) search))
                           subtree)
      :else          (match? subtree (rest path) search))))

(defn- resource-pred [query-params config]
  (let [valid-query-params  (select-keys query-params (map :blaze.fhir.SearchParameter/code config))
        select-path-by-code (fn [config code]
                              (->> config
                                   (filter #(= (:blaze.fhir.SearchParameter/code %) code))
                                   first
                                   :blaze.fhir.SearchParameter/expression))]
    (when (seq valid-query-params)
      (fn [resource] (every? (fn [[path search]] (match? resource path search))
                            (mapv (fn [[k v]] [(select-path-by-code config k) v]) valid-query-params))))))

(defn- entry
  [router {type "resourceType" id "id" :as resource}]
  {:fullUrl (fhir-util/instance-url router type id)
   :resource resource
   :search {:mode "match"}})


(defn- summary?
  "Returns true iff a summary result is requested."
  [{summary "_summary" :as query-params}]
  (or (zero? (fhir-util/page-size query-params)) (= "count" summary)))


(defn- search [router db type query-params config mapping]
  (prn "search")
  ;; TODO: This is where I need to leverage mapping, to make the proper query
  ;; I think I will also need to map the config (which is the search config Drew made)
  (let [pred (resource-pred query-params config)]
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
        ;; TODO: I will have to override this resource-id-attr because it will make
        ;; an ident like :Condition/id where we need :Resource/id... but that will be
        ;; incredibly inefficient, I should try to use our phi.element/type
        (d/datoms db :aevt (util/resource-id-attr type)))))))


(defn- handler-intern [{:keys [database/conn blaze.fhir.SearchParameter/config schema/mapping]}]
  (fn [{{{:fhir.resource/keys [type]} :data} ::reitit/match
       :keys [params]
       ::reitit/keys [router]}]
    (prn "handler-intern")
    (-> (search router (d/db conn) type params config mapping)
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
  [_ config]
  (log/info "Init FHIR search-type interaction handler")
  (handler config))
