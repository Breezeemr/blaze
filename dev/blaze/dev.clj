(ns blaze.dev
  (:require
    [blaze.datomic.util :as datomic-util]
    [blaze.spec]
    [blaze.system :as system]
    [clojure.repl :refer [pst]]
    [clojure.spec.test.alpha :as st]
    [clojure.tools.namespace.repl :refer [refresh]]
    [datomic.api :as d]
    [datomic-spec.test :as dst]))


;; Spec Instrumentation
(st/instrument)
(dst/instrument)


(defonce system nil)

(defn init []
  (alter-var-root #'system (constantly (system/init! (System/getenv))))
  nil)

(defn reset []
  (system/shutdown! system)
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


(defn count-resources [db type]
  (d/q '[:find (count ?e) . :in $ ?id :where [?e ?id]] db (datomic-util/resource-id-attr type)))


(comment
  (def conn (:blaze.database/conn system))
  (def db (d/db conn))
  (def hdb (d/history db))

  (count-resources (d/db conn) "Coding")
  (count-resources (d/db conn) "Organization")
  (count-resources (d/db conn) "Patient")
  (count-resources (d/db conn) "Specimen")
  (count-resources (d/db conn) "Observation")

  (d/pull (d/db conn) '[*] 1262239348687945)
  (d/entity (d/db conn) [:Patient/id "0"])
  (d/q '[:find (pull ?e [*]) :where [?e :code/id]] (d/db conn))

  (d/pull (d/db conn) '[*] (d/t->tx 1197))
  )
