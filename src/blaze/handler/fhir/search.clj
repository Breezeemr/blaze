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


;; TODO: improve quick hack
(defn- resource-pred [db type {:strs [identifier]}]
  (when identifier
    (let [attr (keyword type "identifier")
          {:db/keys [cardinality]} (util/cached-entity db attr)
          matches?
          (fn [{:Identifier/keys [value]}]
            (= identifier value))]
      (fn [resource]
        (let [value (get resource attr)]
          (if (= :db.cardinality/many cardinality)
            (some matches? value)
            (matches? value)))))))

(defn- resource-pred-v2 [db type {:strs [subject]}]
  (when subject
    (let [attr                     (keyword type "subject")
          {:db/keys [cardinality]} (util/cached-entity db attr)
          matches?
          ;; NOTE this Identifier/keys might need to be something else
          (fn [x]
            (= subject (:Patient/id x)))]
      (fn [resource]
        (let [value (get resource attr)]
          (if (= :db.cardinality/many cardinality)
            (some matches? value)
            (matches? value)))))))

;; Generalize v1 and v2
;; NOTE this attempt only tries to allow for any search term, NOT multiple search terms
;; 1. can't destructive because that implies we only care about one search param.
;; 2. need to lookup the validation key in `matches?`

;;TODO What this is doing needs to be made more clear.
;; Its used as part of the validation that the returned
;; value from the database is indeed what the user searched for.
;; This check is used as a filter on the DB sense the db retuns *more*
;; the then search asked for.

(def type+search-param->lookup-attr
  {"Condition" {"subject" :Patient/id}})

;;TODO Merge this map into the one above. Dont overthink it though as
;; Ultamitly will be generating this from the FHIR serach params list
(def valid-search-params #{"identifier" "subject"})

(defn resource-pred-v3 [db type query-params]
  ;;NOTE we assume only one search param so grabbing the first
  (if-some [[search-param search-value] (first (select-keys query-params valid-search-params))]
    (let [attr                     (keyword type search-param)
          lookup-attr              (get-in type+search-param->lookup-attr [type search-param])
          {:db/keys [cardinality]} (util/cached-entity db attr)
          matches?
          (fn [x]
            (= search-value (lookup-attr x)))]
      (fn [resource]
        (let [value (get resource attr)]
          (if (= :db.cardinality/many cardinality)
            (some matches? value)
            (matches? value)))))))

(defn- entry
  [router {type "resourceType" id "id" :as resource}]
  {:fullUrl (fhir-util/instance-url router type id)
   :resource resource
   :search {:mode "match"}})


(defn- summary?
  "Returns true iff a summary result is requested."
  [{summary "_summary" :as query-params}]
  (or (zero? (fhir-util/page-size query-params)) (= "count" summary)))


(defn- search [router db type query-params]
  (let [pred (resource-pred-v3 db type query-params)]
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
