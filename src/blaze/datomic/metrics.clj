(ns blaze.datomic.metrics
  (:require
    [prometheus.alpha :as prom]))


(prom/defcounter object-cache-requests-total
  "Total number of requests to the Datomic object cache."
  {:namespace "datomic"})


(prom/defcounter object-cache-hits-total
  "Total number of hits of the Datomic object cache."
  {:namespace "datomic"})


(prom/defgauge object-cache-size
  "Number of segments in the Datomic object cache."
  {:namespace "datomic"})


(defn handler
  [{object-cache :ObjectCache
    object-cache-count :ObjectCacheCount}]

  (when-let [{:keys [sum count]} object-cache]
    (prom/inc! object-cache-requests-total count)
    (prom/inc! object-cache-hits-total sum))

  (when object-cache-count
    (prom/set! object-cache-size object-cache-count)))
