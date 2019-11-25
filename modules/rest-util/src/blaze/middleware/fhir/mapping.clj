(ns blaze.middleware.fhir.mapping)

(defn wrap-map-schema
  [handler {:keys [schema/mapping]}]
  (fn [request]
    (prn "wrap-map-schema")
    (clojure.pprint/pprint mapping)
    (handler request)))
