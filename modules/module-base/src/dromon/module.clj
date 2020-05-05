(ns dromon.module
  (:require
    [integrant.core :as ig]))


(defmacro reg-collector
  "Registers a metrics collector to the central registry."
  [key collector]
  `(do
     (defmethod ig/init-key ~key ~'[_ _] ~collector)

     (derive ~key :dromon.metrics/collector)))
