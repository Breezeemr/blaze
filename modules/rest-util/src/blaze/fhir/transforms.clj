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

(defn $cr [_ v]
  (coding _ (str/split v #"\u001f")))


(defn reference [k v]
  (into []
        (map (fn [val]
               (let [prefix (-> (:phi.element/type val) (str/split #"\/") second)
                     id     (:fhir.Resource/id val)]
                 {:reference (str prefix "/" id)})))
        (if (sequential? v)
          v
          [v])))


(defn resource-type [_ v]
  (second (str/split v #"\/")))
