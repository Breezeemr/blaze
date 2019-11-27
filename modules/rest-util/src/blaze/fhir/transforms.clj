(ns blaze.fhir.transforms
  (:require
   [clojure.string :as str]))


(defn coding [c]
  (into []
        (comp
         (map #(str/split % #"\/"))
         (map (fn [[system code]]
                {:system system
                 :code   code})))
        c))
