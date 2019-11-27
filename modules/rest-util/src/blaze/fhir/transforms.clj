(ns blaze.fhir.transforms
  (:require
   [clojure.string :as str]))


(defn coding [_ v]
  (into []
        (comp
         (map #(str/split % #"\/"))
         (map (fn [[system code]]
                {:system system
                 :code   code})))
        v))


(defn reference [k v]
  {:reference (str (name k) "/" (:fhir.Resource/id v))})
