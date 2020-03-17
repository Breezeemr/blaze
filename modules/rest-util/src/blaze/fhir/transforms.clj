(ns blaze.fhir.transforms
  (:require
   [clojure.string :as str]
   [clojure.walk :refer [prewalk postwalk]]
   [datomic.api :as d])
  (:import
   (java.time LocalDate ZoneId)))


(defn coding [[system code]]
  {:system  system
   :code    code
   :display code})


(defn coding$cr [{:keys [v]}]
  (into []
        (comp
         (map #(str/split % #"\/"))
         (map coding))
        v))


(defn codeable-concept [{:keys [v]}]
  {:fhir.CodeableConcept/text v
   ;; TODO: Handle v as a string or a list of strings (and therefore a list of codings)
   :fhir.CodeableConcept/coding [(coding (str/split v #"\/"))]})


(defn codeable-concept-many [{:keys [v]}]
  (into []
        (map #(codeable-concept {:v %}))
        (if (sequential? v)
          v
          [v])))


(defn reference [{:keys [v]}]
  (when (map? v)
    (let [prefix (-> (:phi.element/type v) (str/split #"\/") second)
          id     (:fhir.Resource/id v)]
      (when (and prefix id)
        {:reference (str prefix "/" id)}))))


(defn reference-many [{:keys [v]}]
  (into []
        (map #(reference {:v %}))
        v))


(defn consent-patient-reference [{:keys [v]}]
  (let [prefix "Patient" ;; (-> (:phi.element/type v) (str/split #"\/") second)
        id     (:fhir.Reference/reference v)]
    (when id
      {:reference (str prefix "/" id)})))


(defn medication-request-dosage-instruction [{:keys [v]}]
  (let [dosage-instruction      (first v)
        sequence                nil ;; Cabotage wants this but does not exist in this old version of FHIR
        text                    (:fhir.MedicationPrescription.dosageInstruction/text dosage-instruction)
        additional-instructions (:fhir.MedicationPrescription.dosageInstruction/additionalInstructions dosage-instruction)
        coding                  (:coding additional-instructions)
        display                 (:display coding)]
    {}))


(defn resource-type [{:keys [v]}]
  (second (str/split v #"\/")))


(defn inst->date [{:keys [v]}]
  (LocalDate/from (.atZone (.toInstant v)
                           (ZoneId/of "UTC"))))


(defn inst->date->str [{:keys [v]}]
  (str (inst->date {:v v})))


(defn null-separated-list->vec [{:keys [v]}]
  (str/split v #"\u001f"))


(defn extension [{:keys [k v]}]
  {:url       (case k
                :extension/ethnicity "http://hl7.org/fhir/us/core/StructureDefinition/us-core-ethnicity"
                :extension/race      "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race")
   :extension [{:url         "ombCategory"
                :valueCoding (coding (str/split v #"\/"))}]})


(defn merged-extensions [resource]
  (let [ks         (into []
                         (filter #(= "extension" (namespace %)))
                         (keys resource))
        extensions (into []
                         (map second)
                         (select-keys resource ks))
        r          (apply dissoc resource ks)]
    (if (not-empty extensions)
      (assoc r :extension extensions)
      r)))


(defn transform [db mapping resource]
  (->> resource
       ;; Do mappings
       (prewalk (fn [node]
                  (if (vector? node)
                    (let [[k v] node]
                      (if-let [mapper (get mapping k)]
                        (let [new-k (:key mapper k)
                              f     (:value mapper)
                              new-v (if f
                                      ((requiring-resolve f) {:db db :k new-k :v v})
                                      v)]
                          [new-k new-v])
                        node))
                    node)))
       ;; Remove nil values ;; TODO: Incorporate this into the previous walk
       (prewalk (fn [node]
                  (cond
                    (map? node)
                    (apply dissoc
                           node
                           (for [[k v] node :when (nil? v)] k))

                    (sequential? node)
                    (into []
                          (remove nil?)
                          node)

                    :else
                    node)))
       ;; Merge extensions
       merged-extensions))


;;NOTE this seems over done. I have to pull the key based on the value. It usually the
;;reverse.
(defn expression-key->old-blaze-key
  [expression-key mapping]
  (loop [[v {:keys [key]}] (first mapping)
         r                 (rest mapping)]
    (cond
        (= key expression-key) v
        (empty? r) nil
        :else      (recur (first r) (rest r)))))

(defn ->expression
  [expression mapping]
  (reduce
    (fn [coll exp-key]
      (if-let [old-key (expression-key->old-blaze-key exp-key mapping)]
        (conj coll old-key)
        (conj coll exp-key)))
    []
    expression))

(comment

  (def mapping
    {:type                                                  "MedicationPrescription"
     :fhir.MedicationPrescription/dateWritten               {:key :fhir.MedicationRequest/authoredOn}
     :fhir.MedicationPrescription/medication                {:key :fhir.MedicationRequest/medicationReference}
     :fhir.Medication/code$cr                               {:key   :fhir.MedicationRequest/code
                                                             :value blaze.fhir.transforms/codeable-concept}
     :fhir.MedicationPrescription/patient                   {:key :fhir.MedicationRequest/subject}
     :fhir.MedicationPrescription/prescriber                {:key :fhir.MedicationRequest/requester}
     :fhir.MedicationPrescription/additionalInstructions$cr {:key   :fhir.Dosage/additionalInstructions
                                                             :value blaze.fhir.transforms/codeable-concept}
     :fhir.MedicationPrescription.dosageInsruction/rate     {:key :fhir.Dosage.rateAndQuantity/rateRatio}})


  (expression-key->old-blaze-key :fhir.MedicationRequest/subject mapping)
  ;; => :fhir.MedicationPrescription/patient

  (expression-key->old-blaze-key :foobar mapping)
  ;; => nil

  (->expression
    [:fhir.MedicationRequest/subject :fhir.Resource/id]
    mapping)

  ;; => [:fhir.MedicationPrescription/patient :fhir.Resource/id]

  )
