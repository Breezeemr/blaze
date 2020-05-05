(ns dromon.elm.compiler.function
  (:require [cognitect.anomalies :as anom]
            [dromon.anomaly :refer [throw-anom]]
            dromon.datomic.quantity
            [dromon.elm.code :as code]
            [dromon.elm.compiler.protocols :refer [-eval Expression]]
            [dromon.elm.protocols :as p]
            [dromon.elm.quantity :as q])
  (:import [dromon.datomic.quantity CustomQuantity UcumQuantityWithDifferentUnit UcumQuantityWithoutUnit UcumQuantityWithSameUnit]))

(defprotocol ToQuantity
  (-to-quantity [x]))


(extend-protocol ToQuantity
  UcumQuantityWithoutUnit
  (-to-quantity [x]
    (q/quantity (.value x) (.code x)))

  UcumQuantityWithSameUnit
  (-to-quantity [x]
    (q/quantity (.value x) (.code x)))

  UcumQuantityWithDifferentUnit
  (-to-quantity [x]
    (q/quantity (.value x) (.code x)))

  CustomQuantity
  (-to-quantity [x]
    (throw-anom
      ::anom/unsupported
      (format "Unsupported non-UCUM quantity `%s`." (into {} x))))

  Object
  (-to-quantity [x]
    (throw-anom
      ::anom/fault
      (format "Can't convert `%s` to quantity." x)))

  nil
  (-to-quantity [_]))


(defrecord ToQuantityFunctionExpression [operand]
  Expression
  (-eval [_ context resource scope]
    (-to-quantity (-eval operand context resource scope))))


(defrecord ToCodeFunctionExpression [operand]
  Expression
  (-eval [_ context resource scope]
    (let [coding (-eval operand context resource scope)
          {{:code/keys [system version code]} :Coding/code} coding]
      (code/to-code system version code))))


(defrecord ToDateFunctionExpression [operand]
  Expression
  (-eval [_ {:keys [now] :as context} resource scope]
    (p/to-date (-eval operand context resource scope) now)))


(defrecord ToDateTimeFunctionExpression [operand]
  Expression
  (-eval [_ {:keys [now] :as context} resource scope]
    (p/to-date-time (-eval operand context resource scope) now)))


(defrecord ToStringFunctionExpression [operand]
  Expression
  (-eval [_ context resource scope]
    (let [value (-eval operand context resource scope)]
      (str (or (:code/code value) value)))))
