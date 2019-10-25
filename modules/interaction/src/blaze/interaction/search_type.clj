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

(def res-type+search->matches-fn
  {"Condition"          {"subject"  {:matches-fn match-reference?
                                     :attr       :Condition/subject}
                         "patient"  {:matches-fn match-reference?
                                     :attr       :Condition/subject}
                         "category" {:matches-fn match-codeable-concept?
                                     :attr       :Condition/category}}
   "MedicationRequest"  {"subject" {:matches-fn match-reference?
                                    :attr       :MedicationRequest/subject}
                         "patient" {:matches-fn match-reference?
                                    :attr       :MedicationRequest/subject}}
   "ServiceRequest"     {"subject" {:matches-fn match-reference?
                                    :attr       :ServiceRequest/subject}
                         "patient" {:matches-fn match-reference?
                                    :attr       :ServiceRequest/subject}}
   "Goal"               {"subject" {:matches-fn match-reference?
                                    :attr       :Goal/subject}
                         "patient" {:matches-fn match-reference?
                                    :attr       :Goal/subject}}
   "Procedure"          {"subject" {:matches-fn match-reference?
                                    :attr       :Procedure/subject}
                         "patient" {:matches-fn match-reference?
                                    :attr       :Procedure/subject}}
   "AllergyIntolerance" {"patient" {:matches-fn match-reference?
                                    :attr       :AllergyIntolerance/patient}}
   "Device"             {"patient" {:matches-fn match-reference?
                                    :attr       :Device/patient}}
   "Immunization"       {"patient" {:matches-fn match-reference?
                                    :attr       :Immunization/patient}}
   "Observation"        {"patient"  {:matches-fn match-reference?
                                     :attr       :Observation/subject}
                         "category" {:matches-fn match-codeable-concept?
                                     :attr       :Observation/category}}
   "DiagnosticReport"   {"subject" {:matches-fn match-reference?
                                    :attr       :DiagnosticReport/subject}
                         "patient" {:matches-fn match-reference?
                                    :attr       :DiagnosticReport/subject}}
   "CarePlan"           {"subject" {:matches-fn match-reference?
                                    :attr       :CarePlan/subject}
                         "patient" {:matches-fn match-reference?
                                    :attr       :CarePlan/subject}}
   ;; An "actor" is the entity with some action
   ;; over a patients information
   ;;TODO will probably need to check "action"
   "Consent"            {"patient" {:matches-fn match-reference?
                                    :attr       :Consent/patient}
                         "actor"   {:matches-fn match-actor?
                                    :attr       :Consent/provision}}
   "identifier"         match-identifier?})

(defn- resource-pred
  [db type query-params]
  (let [search-info (reduce-kv
                      (fn [coll search-param search-value]
                        (if-let [matches-fn (get-in res-type+search->matches-fn [type search-param :matches-fn])]
                          (let [attr (get-in res-type+search->matches-fn [type search-param :attr])]
                            (conj coll
                                  {:search-param search-param
                                   :search-value search-value
                                   :matches-fn   matches-fn
                                   :attr         attr
                                   :cardinality  (:db/cardinality (util/cached-entity db attr))}))
                          coll))
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


(defn- search [router db type query-params]
  (let [pred (resource-pred db type query-params)]
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


(defmethod ig/init-key :blaze.interaction/search-type
  [_ {:database/keys [conn]}]
  (log/info "Init FHIR search-type interaction handler")
  (handler conn))
