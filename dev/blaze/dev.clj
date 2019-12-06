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

  (into []
        (comp
         (map #(transforms/transform db mapping %)))
        d)

  )
