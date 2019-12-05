(ns blaze.fhir.transforms
  (:require
   [clojure.string :as str]
   [clojure.walk :refer [prewalk postwalk]]))


(defn coding [{:keys [v]}]
  (into []
        (comp
         (map #(str/split % #"\/"))
         (map (fn [[system code]]
                {:system system
                 :code   code})))
        v))

(defn $cr [k v]
  (coding k (str/split v #"\u001f")))


(defn reference-one [{:keys [v]}]
  (let [prefix (-> (:phi.element/type v) (str/split #"\/") second)
        id     (:fhir.Resource/id v)]
    (when id
      {:reference (str prefix "/" id)})))


(defn reference-many [{:keys [v]}]
  (into []
        (map #(reference-one {:v %}))
        (if (sequential? v)
          v
          [v])))


(defn consent-patient-reference [{:keys [v]}]
  (let [prefix (-> (:phi.element/type v) (str/split #"\/") second)
        id     (:fhir.Reference/reference v)]
    (when id
      {:reference (str prefix "/" id)})))


(defn medication-request-medication-reference->codeable-concept [{:keys [v]}]
  ;; (prn "thing::" v)
  (reference-one {:v v}))


(defn resource-type [{:keys [v]}]
  (second (str/split v #"\/")))


(defn transform [mapping resource]
  (->> (prewalk (fn [node]
                  (if (vector? node)
                    (let [[k v] node]
                      ;; (prn k v node)
                      (if-let [mapper (get mapping k)]
                        (let [new-k (:key mapper k)
                              f     (:value mapper)
                              new-v ((requiring-resolve f) {:k new-k :v v})]
                          [new-k new-v])
                        node))
                    node))
                resource)
       ;; Remove nil values
       ;; TODO: Figure out how to do this in one single walk
       (prewalk (fn [node]
                  (if (map? node)
                    (apply dissoc
                           node
                           (for [[k v] node :when (nil? v)] k))
                    node)))))
