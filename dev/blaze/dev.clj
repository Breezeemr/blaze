(ns blaze.dev
  (:require
    [blaze.spec]
    [blaze.system :as system]
    [clojure.repl :refer [pst]]
    [clojure.spec.test.alpha :as st]
    [clojure.tools.namespace.repl :refer [refresh]]
    [datomic-spec.test :as dst]))


;; Spec Instrumentation
(st/instrument)
(dst/instrument)


(defonce system nil)


(defn init []
  (alter-var-root #'system (constantly (system/init! (System/getenv))))
  nil)


(defn reset []
  (some-> system system/shutdown!)
  (refresh :after `init))


;; Init Development
(comment
  (init)
  (pst)
  )


;; Reset after making changes
(comment
  (reset)
  (st/unstrument)
  )



(comment

  (def d [{:fhir.Observation/component
           [{:db/id                              17592199351058,
             :phi.element/type                   "fhir-type/Observation.Component",
             :fhir.Observation.component/code$cr "SNOMEDCT_US/42959003",
             :fhir.Observation.component/code
             {:db/id                          17592199351059,
              :phi.element/type               "fhir-type/CodeableConcept",
              :fhir.CodeableConcept/text      "Flare reaction",
              :fhir.CodeableConcept/coding$cr ["SNOMEDCT_US/42959003"]},
             :fhir.Observation.component/valueQuantity
             {:db/id                17592199351060,
              :phi.element/type     "fhir-type/Quantity",
              :fhir.Quantity/system (java.net.URI. "http://unitsofmeasure.org"),
              :fhir.Quantity/value  0M,
              :fhir.Quantity/code   "mm{flare}",
              :fhir.Quantity/unit   "mm{flare}"},
             :breeze.Component/order             1}
            {:db/id                              17592199351061,
             :phi.element/type                   "fhir-type/Observation.Component",
             :fhir.Observation.component/code$cr "SNOMEDCT_US/54047008",
             :fhir.Observation.component/code
             {:db/id                          17592199351062,
              :phi.element/type               "fhir-type/CodeableConcept",
              :fhir.CodeableConcept/text      "Wheal reaction",
              :fhir.CodeableConcept/coding$cr ["SNOMEDCT_US/54047008"]},
             :fhir.Observation.component/valueQuantity
             {:db/id                17592199351063,
              :phi.element/type     "fhir-type/Quantity",
              :fhir.Quantity/system (java.net.URI. "http://unitsofmeasure.org"),
              :fhir.Quantity/value  0M,
              :fhir.Quantity/code   "mm{wheal}",
              :fhir.Quantity/unit   "mm{wheal}"},
             :breeze.Component/order             0}],
           :fhir.Observation/subject   #:db{:id 17592186355869},
           :phi.element/type           "fhir-type/Observation",
           :fhir.Observation/status    "final",
           :fhir.Observation/encounter #:db{:id 17592199138365},
           :db/id                      17592199351057,
           :breeze.Component/order     32,
           :fhir.Observation/code
           {:db/id                          17592199351064,
            :phi.element/type               "fhir-type/CodeableConcept",
            :fhir.CodeableConcept/text      "Alternaria",
            :fhir.CodeableConcept/coding$cr ["SNOMEDCT_US/411813007"]},
           :fhir.Observation/status$cr "fhir.observation-status/final"}])

  (require '[datomic.api :as d])
  (require '[blaze.fhir.transforms :as transforms])

  (def conn (d/connect (System/getenv "DATABASE_URI")))
  (def db (d/db conn))
  (def mapping {})

  (into []
        (comp
         (map #(transforms/transform db mapping %)))
        d)

  )
