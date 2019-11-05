(ns blaze.handler.app
  (:require
   [blaze.middleware.json :refer [wrap-json]]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [reitit.ring]
   [ring.util.response :as ring]
   [taoensso.timbre :as log]))


(defn router [health-handler]
  (reitit.ring/router
    [["/health"
      {:head health-handlers
       :get  health-handlers}]
     ;; NOTE these are removed in the upstream branch. maybe there elsewhere, as they seem critical.
     ["/fhir"
      {:middleware [wrap-json (:middleware/authentication middleware) wrap-remove-context-path]
       :handler    (:handler.fhir/core handlers)}]
     ["/fhir/{*more}"
      {:middleware [wrap-json (:middleware/authentication middleware) wrap-remove-context-path]
       :handler    (:handler.fhir/core handlers)}]]
    {:syntax :bracket
     :reitit.ring/default-options-handler
     (fn [_]
       (-> (ring/response nil)
           (ring/status 405)))}))


(s/fdef handler
  :args (s/cat :rest-api fn? :health-handler fn?))

(defn handler
  "Whole app Ring handler."
  [rest-api health-handler]
  (reitit.ring/ring-handler
    (router health-handler)
    rest-api))


(defmethod ig/init-key :blaze.handler/app
  [_ {:keys [rest-api health-handler]}]
  (log/info "Init app handler")
  (handler rest-api health-handler))
