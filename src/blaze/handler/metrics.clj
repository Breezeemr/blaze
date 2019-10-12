(ns blaze.handler.metrics
  (:require
    [integrant.core :as ig]
    [prometheus.alpha :as prom]
    [taoensso.timbre :as log]))


(defn metrics-handler
  "Returns a handler function that dumps the metrics associated with `registry`
   in a format consumable by prometheus."
  [registry]
  (fn [_]
    (prom/dump-metrics registry)))


(defmethod ig/init-key :blaze.handler/metrics
  [_ {:keys [registry]}]
  (log/info "Init metrics handler")
  (metrics-handler registry))
