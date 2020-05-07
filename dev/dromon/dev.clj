(ns dromon.dev
  (:require [clojure.repl :refer [pst]]
            [clojure.spec.test.alpha :as st]
            [clojure.tools.namespace.repl :refer [refresh]]
            [datomic-spec.test :as dst]
            [clojure.java.io :as io]
            dromon.spec
            [dromon.system :as system]))

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
