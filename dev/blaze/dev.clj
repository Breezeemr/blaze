(ns blaze.dev
  (:require
    [blaze.spec]
    [blaze.system :as system]
    [clojure.repl :refer [pst]]
    [clojure.spec.test.alpha :as st]
    [clojure.spec.alpha :as s]
    [expound.alpha :as expound]
    [clojure.tools.namespace.repl :refer [refresh]]
    [datomic-spec.test :as dst]))

;; Spec Instrumentation
(st/instrument)
(dst/instrument)

;;Set spec errors to be human readable
(set! s/*explain-out* expound/printer)


(defonce system nil)


(defn init []
  (alter-var-root #'system (constantly (system/init! (System/getenv))))
  nil)


(defn reset []
  (some-> system system/shutdown!)
  (refresh :after `init))

;; Init Development: You have to run this to get going
(comment
  (init)
  (pst))


;; run this to reset the system after making changes to the code.
(comment
  (reset)

  ;;TODO document why we need to run unstrument?
  (st/unstrument))


;; Code from here down are examples

(comment

  (require '[datomic.api :as d])
  (require '[blaze.fhir.transforms :as transforms])

  (def conn (d/connect (System/getenv "DATABASE_URI")))
  (def db (d/db conn))
  (def mapping {})
  (def john-doe-dev-fhir-uuid (java.util.UUID/fromString "55776ed1-2072-4d0c-b19f-a2d725aadf15"))

  (d/q '[:find ?d .
         :in $ ?id
         :where
         [?p :fhir.Resource/id ?id]
         [?r :fhir.v3.AllergyIntolerance/patient ?p]
         [?r :fhir.v3.AllergyIntolerance/assertedDate ?d]
         ]
    db john-doe-dev-fhir-uuid)
  )


(comment

  (def d [{:db/id                         17592190724169,
           :phi.element/type              "fhir-type/DiagnosticReport",
           :fhir.DiagnosticReport/subject #:db{:id 17592186507202},
           :fhir.DiagnosticReport/issued  #inst "2016-02-24T22:44:00.091-00:00",
           :fhir.DiagnosticReport/status  "final",
           :fhir.Resource/id              #uuid "66eb00b9-f451-5e97-a714-3195c0663d30",
           :fhir.DiagnosticReport/result
           [#:db{:id 17592190716161}
            #:db{:id 17592190716166}
            #:db{:id 17592190716169}
            #:db{:id 17592190716174}
            #:db{:id 17592190716179}
            #:db{:id 17592190716182}
            #:db{:id 17592190716185}
            #:db{:id 17592190716188}],
           :fhir.Resource/meta
           {:db/id            17592228316897,
            :phi.element/type "fhir-type/Meta",
            :fhir.Meta/tag$cr ["breeze/reviewed"]}}])

  (require '[datomic.api :as d])
  (require '[blaze.fhir.transforms :as transforms])

  (def conn (d/connect (System/getenv "DATABASE_URI")))
  (def db (d/db conn))
  (def mapping {})

  (d/pull (d/db conn) '[*] [:fhir.Resource/id #uuid "57896222-94d8-4313-9500-ceeaea7d568c"])

  (into [] (d/datoms (d/db conn) :avet :phi.element/type (str "fhir-type/" "AllergyIntolerance")))
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?type
         :where
         [?e :phi.element/type ?type]]
       (d/db conn)
       (str "fhir-type/" "Observation"))

  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?type ?uuid
         :where
         ;; [?e :phi.element/type ?type]
         [?subject :fhir.Resource/id ?uuid]
         [?e :fhir.DiagnosticReport/subject ?subject]]
       (d/db conn)
       (str "fhir-type/" "DiagnosticReport")
       #uuid "55776ed1-2072-4d0c-b19f-a2d725aadf15")

  (into []
        (comp
         (map #(transforms/transform db mapping %)))
        d)


  (d/q '[:find (pull ?e [* {:fhir.Condition/patient [:fhir.Resource/id]}])
         :in $ ?uuid
         :where
         [?id :fhir.Resource/id ?uuid]
         [?e :fhir.Condition/patient ?id]]
       (d/db conn)
       #uuid "55776ed1-2072-4d0c-b19f-a2d725aadf15")

  (def consent-conn (d/connect (System/getenv "CONSENT_DATABASE_URI")))

  (d/pull (d/db conn) '[* {:fhir.Consent/patient [*]}] [:fhir.Resource/id #uuid "59ddd097-8acb-413c-ba7a-8ddc630994b3"])

  ;; All consents
  (d/q '[:find [(pull ?e [:db/id
                          :fhir.Resource/id
                          :phi.element/type
                          :fhir.Consent/status
                          {:fhir.Consent/patient [:fhir.Reference/reference :phi.element/type]
                           :fhir.Consent/provision
                           [{:fhir.Consent.provision/actor
                             [{:fhir.Consent.provision.actor/reference [:fhir.Reference/reference]}]}]}
                          ])
                ...]
         :in $
         :where [?e :fhir.Consent/patient]]
       (d/db local-consent-conn))

  (d/pull (d/db consent-conn) '[*] :phi.element/type)

  ;; Schema updates
  @(d/transact consent-conn [{:db/id :fhir.Consent/status :db/ident :DEPRECATED_fhir.Consent/status}])
  @(d/transact consent-conn [{:db/ident       :fhir.Consent/status
                              :db/valueType   :db.type/string
                              :db/cardinality :db.cardinality/one}])
  @(d/transact consent-conn [{:db/ident       :phi.element/type
                              :db/valueType   :db.type/string
                              :db/cardinality :db.cardinality/one
                              :db/index       true}])

  ;; Add provision.actor to consents
  @(d/transact consent-conn [{:db/id                  17592186045505,
                              :fhir.Consent/provision {:fhir.Consent.provision/actor
                                                       {:fhir.Consent.provision.actor/reference 17592186045500}}
                              :fhir.Consent/status    "active"
                              :phi.element/type       "fhir-type/Consent"
                              }
                             {:db/id                  17592186045499,
                              :fhir.Consent/provision {:fhir.Consent.provision/actor
                                                       {:fhir.Consent.provision.actor/reference 17592186045500}}
                              :fhir.Consent/status    "active"
                              :phi.element/type       "fhir-type/Consent"
                              }
                             {:db/id                  17592186045502,
                              :fhir.Consent/provision {:fhir.Consent.provision/actor
                                                       {:fhir.Consent.provision.actor/reference 17592186045500}}
                              :fhir.Consent/status    "active"
                              :phi.element/type       "fhir-type/Consent"
                              }])

  (d/pull (d/db consent-conn) '[* {:db/valueType [*] :db/cardinality [*]}] :fhir.Consent/category)
  (d/pull (d/db consent-conn) '[*] 17592186045500)

  (d/q '[:find [(pull ?consent [:fhir.Resource/id
                                :fhir.Consent/status
                                {:fhir.Consent/patient [:fhir.Reference/reference]}])
                ...]
         :in $ ?actor-id
         :where
         [?actor :fhir.Reference/reference ?actor-id]
         [?ref :fhir.Consent.provision.actor/reference ?actor]
         [?provision :fhir.Consent.provision/actor ?ref]
         [?consent :fhir.Consent/provision ?provision]]
       (d/db consent-conn)
       "55776ed1-2072-4d0c-b19f-a2d725aadf15")


  (def local-consent-conn (d/connect "datomic:dev://localhost:4334/bundle-test"))
  ()

  )
