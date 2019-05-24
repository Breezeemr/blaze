(ns blaze.datomic.quantity
  (:require
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom])
  (:import
    [javax.measure Unit]
    [javax.measure.format UnitFormat]
    [systems.uom.ucum.format UCUMFormat$Variant UCUMFormat]
    [tec.uom.se.quantity Quantities]))


(def ^:private ^UnitFormat ucum-format
  (UCUMFormat/getInstance UCUMFormat$Variant/CASE_SENSITIVE))


(defn- parse-unit* [s]
  (try
    (.parse ucum-format s)
    (catch Throwable t
      (throw (ex-info (str "Problem while parsing the unit `" s "`.")
                      (cond->
                        {::anom/category ::anom/incorrect
                         :unit s}
                        (.getMessage ^Throwable t)
                        (assoc :cause-msg (.getMessage ^Throwable t))))))))


(let [mem (volatile! {})]
  (defn- parse-unit [s]
    (if-let [unit (get @mem s)]
      unit
      (let [unit (parse-unit* s)]
        (vswap! mem assoc s unit)
        unit))))


(s/fdef quantity
  :args (s/cat :value decimal? :unit (s/nilable string?)))

(defn quantity
  "Creates a quantity with numerical value and string unit."
  [value unit]
  (Quantities/getQuantity value (parse-unit (or unit ""))))


(defn unit? [x]
  (instance? Unit x))


(s/fdef format-unit
  :args (s/cat :unit unit?))

(defn format-unit
  "Formats the unit after UCUM so that it is parsable again."
  [unit]
  (.format ucum-format unit))
