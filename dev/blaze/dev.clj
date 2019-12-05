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

  (def d [{:fhir.Resource/id              #uuid "43a21082-d525-5d23-b76e-2cc420753eb3",
           :fhir.Patient/address
           [{:fhir.Address/state      "LA",
             :fhir.Address/line       "401 Youngsville Hwy Suite 10",
             :breeze.Component/id     #uuid "3dc8ea1d-ae65-5e1c-8369-bad7657833b8",
             :phi.element/type        "fhir-type/Address",
             :fhir.Address/use        "home",
             :fhir.Address/use$cr     "fhir.address-use/home",
             :db/id                   17592269286658,
             :fhir.Address/postalCode "70508",
             :fhir.Address/city       "Lafayette"}],
           :breeze.Patient/ethnicity$cr      "fhir.v3.Ethnicity/2186-5",
           ;; :breeze.Patient/vfcEligibility$cr "V02",
           ;; :fhir.Patient/careProvider        [#:db{:id 17592186047919}],
           ;; :fhir.Patient/identifier$id
           ;; ["links.Patient/2027328"
           ;;  "pgoa.dux.Patient/276739"
           ;;  "pgoa.links.MRN/1000027382"],
           ;; :phi.element/type                 "fhir-type/Patient",
           :fhir.Patient/birthDate        #inst "2001-01-01T00:00:00.000-00:00",
           :fhir.Patient/name
           [{:db/id                 17592269286659,
             :phi.element/type      "fhir-type/HumanName",
             :fhir.HumanName/text   "John Doe",
             :fhir.HumanName/given  "John",
             :fhir.HumanName/family "Doe",
             :fhir.HumanName/use    "usual",
             :fhir.HumanName/use$cr "fhir.name-use/usual",
             :breeze.Component/id   #uuid "8b2496d5-267b-543e-af9b-b1040a187607"}],
           ;; :breeze.Patient/race$cr           "fhir.v3.Race/2028-9",
           ;; :fhir.Patient/active              true,
           :fhir.Patient/communication
           [{:db/id                                17592276015354,
             :phi.element/type                     "fhir-type/Patient.Communication",
             :breeze.Component/id                  #uuid "45675671-0c78-5c5c-ba67-512c615791c3",
             :fhir.Patient.communication/language
             {:db/id                          17592276015355,
              :phi.element/type               "fhir-type/CodeableConcept",
              :fhir.CodeableConcept/text      "English",
              :fhir.CodeableConcept/coding$cr ["ietf.bcp47/en"],
              :breeze.Component/id            #uuid "ef405b79-7307-5a09-ac1a-44d57a196007"},
             :fhir.Patient.communication/preferred true}],
           ;; :fhir.Resource/meta
           ;; {:db/id            17592269286673,
           ;;  :phi.element/type "fhir-type/Meta",
           ;;  :fhir.Meta/tag$cr
           ;;  ["breeze.immu-registry.patient-match/exact"
           ;;   "breeze/breeze-manages-allergy-list"]},
           ;; :db/id                            17592269286657,
           :fhir.Patient/maritalStatus$cr "fhir.v3.MaritalStatus/S",
           :fhir.Patient/gender           "male",
           :fhir.Patient/telecom
           [{:db/id                       17592269286660,
             :phi.element/type            "fhir-type/ContactPoint",
             :fhir.ContactPoint/system$cr "fhir.contact-system/phone",
             :fhir.ContactPoint/use$cr    "fhir.contact-use/home",
             :breeze.Component/id         #uuid "be1a41fa-bf94-52fb-8600-1e8bb2d13c3a",
             :fhir.ContactPoint/value     "3373300034",
             :fhir.ContactPoint/system    "phone",
             :fhir.ContactPoint/use       "home"}
            {:db/id                       17592269286661,
             :phi.element/type            "fhir-type/ContactPoint",
             :fhir.ContactPoint/system$cr "fhir.contact-system/phone",
             :fhir.ContactPoint/use$cr    "fhir.contact-use/mobile",
             :breeze.Component/id         #uuid "a62c7387-b016-527b-81f5-06430c0689af",
             :fhir.ContactPoint/value     "3373333333",
             :fhir.ContactPoint/system    "phone",
             :fhir.ContactPoint/use       "mobile"}]
           }])

  (require '[datomic.api :as d])
  (require '[blaze.fhir.transforms :as transforms])

  (def conn (d/connect (System/getenv "DATABASE_URI")))
  (def db (d/db conn))
  (def mapping {:fhir.Patient/birthDate        {:value 'blaze.fhir.transforms/inst->date->str}
                :fhir.Patient/maritalStatus$cr {:key   :fhir.Patient/maritalStatus
                                                :value 'blaze.fhir.transforms/codeable-concept}
                ;; :breeze.Patient/ethnicity$cr  {}  ;; TODO: This uses an extension, will probably need to look into the json data files
                ;; These below can be moved to default
                :fhir.HumanName/given          {:value 'blaze.fhir.transforms/null-separated-list->vec}
                })

  (into []
        (comp
         (map #(transforms/transform db mapping %)))
        d)

  )
