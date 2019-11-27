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
  (let [prefix (-> (:phi.element/type v) (str/split #"\/") second)
        id     (:fhir.Resource/id v)]
    {:reference (str prefix "/" id)}))


(defn resource-type [_ v]
  (second (str/split v #"\/")))
