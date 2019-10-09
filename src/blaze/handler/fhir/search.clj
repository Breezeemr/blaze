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

;; version 2 attempts to handle one case of a reference search. A Condition with a subject.
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

(def type+search-param->lookup-attr
  {"Condition" {"subject" :Patient/id}})

(def valid-search-params #{"identifier" "subject" "code"})

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

;; Version 4 attempts to tackle a more complex search. One found in the `tokens` secion.
;; The example is to return a Condition based on a code. e.g A condition with the code
;; "M06.9". The schema is
;; Condition -has-one--> code ---has-many-> coding --- has-one--> code
;; So the search would return any Condition whose coding.code matched. This involves
;; a filter which the previous resource-pred's didnt need. The logic to filter becomes
;; more complex

(defn by-condition-code
  [search resource]
  (filter #(= search (->> % :Coding/code :code/code))
          (:CodeableConcept/coding resource)))

(def type+search-param->matches?
  ;;NOTE overloading the word resource.
  {"Condition" {"subject" (fn [search resource] (= (:Patient/id resource)) search)
                "code"    by-condition-code }})

(defn resource-pred-v4
  [db type query-params]
  ;;NOTE we assume only one search param so grabbing the first
  (if-some [[search-param search-value] (first (select-keys query-params valid-search-params))]
    (let [attr                     (keyword type search-param)
          matches?                 (get-in type+search-param->matches? [type search-param])
          {:db/keys [cardinality]} (util/cached-entity db attr)]
      (fn [resource]
        (let [r (get resource attr)]
          (if (= :db.cardinality/many cardinality)
            (some (partial matches? search-value) r)
            (matches? search-value r)))))))

;; Version 5


(def get-valid-search-params #{"identifier" "subject" "code"})

;; handles multiple arguments, there might be another way to do this
;; thats more high level then reduce
;; and we can defiantly short circuit


;; Note not yet tested
(defn resource-pred-v5
  [db type query-params]
  ;;NOTE we assume only one search param so grabbing the first
  (if-some [valid-search-params (select-keys query-params get-valid-search-params)]
    (let [foo (reduce-kv
                (fn [coll search-param search-value]
                  (let [attr (keyword type search-param)]
                    (conj coll
                          {:search-param search-param
                           :search-value search-value
                           :matches-fn   (get-in type+search-param->matches? [type search-param])
                           :attr         attr
                           :cardinality  (:db/cardinality (util/cached-entity db attr))})))
                []
                valid-search-params)]
      (fn [resource]
        ;;TODO consider makeing it so matches functions return true instead of match?
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
  (let [pred (resource-pred-v5 db type query-params)]
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
