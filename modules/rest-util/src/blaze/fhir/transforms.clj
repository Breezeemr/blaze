(ns blaze.fhir.transforms
  (:require
   [clojure.string :as str]
   [clojure.walk :refer [prewalk]]))


(defn coding [_ v]
  (into []
        (comp
         (map #(str/split % #"\/"))
         (map (fn [[system code]]
                {:system system
                 :code   code})))
        v))

(defn $cr [k v]
  (coding k (str/split v #"\u001f")))


(defn reference [_ v]
  (into []
        (map (fn [val]
               (let [prefix (-> (:phi.element/type val) (str/split #"\/") second)
                     id     (:fhir.Resource/id val)]
                 {:reference (str prefix "/" id)})))
        (if (sequential? v)
          v
          [v])))


(defn consent-patient-reference [_ v]
  (let [prefix (-> (:phi.element/type v) (str/split #"\/") second)
        id     (:fhir.Reference/reference v)]
    {:reference (str prefix "/" id)}))


(defn resource-type [_ v]
  (second (str/split v #"\/")))


(defn transform [mapping resource]
  (prewalk (fn [node]
             (if (vector? node)
               (let [[k v] node]
                 (if-let [mapper (get mapping k)]
                   (let [new-k (:key mapper k)
                         f     (:value mapper)]
                     [new-k
                      ((requiring-resolve f) new-k v)])
                   node))
               node))
           resource))
