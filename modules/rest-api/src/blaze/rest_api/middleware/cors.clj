(ns blaze.rest-api.middleware.cors
  (:require
   [manifold.deferred :as md]
   [taoensso.timbre :as log]
   [integrant.core :as ig]
   [clojure.string :as str]))

(defmethod ig/init-key :blaze.rest-api.middleware.cors/get-wrap-cors
  [_ {:keys [cors/allowed-origins?]}]
  (log/info "Init CORS")
  (fn [handler]
    (fn [{{origin "origin"} :headers
         method :request-method
         :as               request}]
      (let [allowed-headers ["Accept" "Content-Type" "Authorization"]
            headers {"Access-Control-Allow-Origin"  origin
                     "Access-Control-Allow-Methods" "GET, OPTIONS"
                     "Access-Control-Max-Age"       "3600"}
            allowed-origins? (-> allowed-origins? (str/split #",") set)]
        (cond
          (not (allowed-origins? origin)) (md/success-deferred {:status 403
                                                                :body "Unauthorized"})
          (= :options method)              (md/success-deferred {:status 200
                                                                 :headers (assoc headers "Access-Control-Allow-Headers" (str/join ", " (conj allowed-headers "X-PINGOTHER")) )})
          :else
          (md/chain' (handler request) (fn [response]
                                         (update response :headers (fnil into {}) (assoc headers "Access-Control-Allow-Headers" (str/join ", " allowed-headers) )))))))))
