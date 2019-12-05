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


(def d [{:fhir.Immunization/reported false,
         :fhir.Resource/id                  #uuid "59492c63-f4d7-4937-8576-d3746f8b5c3a",
         :fhir.Immunization/encounter       #:db{:id 17592201236741},
         :fhir.Immunization/vaccineCode
         {:db/id                          17592201239902,
          :phi.element/type               "fhir-type/CodeableConcept",
          :fhir.CodeableConcept/text      "Hep A, ped/adol, 2 dose [HAVRIX-PEDS]",
          :fhir.CodeableConcept/coding$cr ["CVX.MVX/83.SKB" "CVX/85"]},
         :fhir.Immunization/site$cr         "fhir.v3.ActSite/RT",
         :fhir.Immunization/status          "in-progress",
         :phi.element/type                  "fhir-type/Immunization",
         :fhir.Immunization/requester       #:db{:id 17592186048011},
         :fhir.Immunization/wasNotGiven     false,
         :fhir.Immunization/lotNumber       "GB242",
         :fhir.Immunization/doseQuantity
         {:db/id               17592218731896,
          :phi.element/type    "fhir-type/SimpleQuantity",
          :fhir.Quantity/value 0.5M,
          :fhir.Quantity/unit  "CC"},
         :fhir.Immunization/manufacturer    #:db{:id 17592193177108},
         :fhir.Resource/meta
         {:db/id            17592218731920,
          :phi.element/type "fhir-type/Meta",
          :fhir.Meta/tag$cr ["breeze/sent-to-links"]},
         :db/id                             17592201239901,
         :fhir.Immunization/route$cr        "fhir.v3.RouteOfAdministration/IM",
         :fhir.Immunization/date            #inst "2017-06-20T14:08:35.360-00:00",
         :fhir.Immunization/patient  #:db{:id 17592189746641}}])



(comment

  (require '[datomic.api :as d])
  (require '[blaze.fhir.transforms :as transforms])

  (def conn (d/connect (System/getenv "DATABASE_URI")))
  (def db (d/db conn))
  (def mapping {:fhir.Immunization/vaccineCode$cr {:key   :fhir.Immunization/vaccineCode
                                                   :value 'blaze.fhir.transforms/codeable-concept}
                :fhir.Immunization/date {:key :fhir.Immunization/occurenceDateTime}})

  (into []
        (comp
         (map #(transforms/transform db mapping %)))
        d)

  )
