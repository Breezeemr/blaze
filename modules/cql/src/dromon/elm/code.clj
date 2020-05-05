(ns dromon.elm.code
  "Implementation of the code type."
  (:require [clojure.spec.alpha :as s]
            [dromon.elm.protocols :as p]))

(defrecord Code [system version code]
  p/Equivalent
  (equivalent [_ other]
    (and (some? other)
         (= system (:system other))
         (= code (:code other)))))


(defn code? [x]
  (instance? Code x))


(s/fdef to-code
  :args (s/cat :system string? :version (s/nilable string?) :code string?)
  :ret code?)

(defn to-code
  "Returns a CQL code with isn't the same as a FHIR code from Datomic."
  [system version code]
  (->Code system version code))
