(ns blaze.rest-api.middleware.cors
  (:require
   [manifold.deferred :as md]
   [clojure.string :as str]))

(def allowed-origins
  #{"https://localhost:8700"
    "http://localhost:8700"
    "https://portal.breezeehr.com"})

(defn allowed-request?
  [{{origin "origin"} :headers}]
  (allowed-origins origin))

(defn get-wrap-cors
  "Fills the traditional role of rejecting requests from sources we dont trust. Traditionally cors deals
  with just origins. 
  Takes a function which will return a falsy value if the request is denied.
  "
  [allowed-request?]
  (fn [handler]
    (fn [{{origin "origin"} :headers
         method :request-method
         :as               request}]
      (let [allowed-headers ["Accept" "Content-Type" "Authorization"]
            headers {"Access-Control-Allow-Origin"  origin
                     "Access-Control-Allow-Methods" "GET, OPTIONS"
                     "Access-Control-Max-Age"       "3600"}]
        (cond
          (not (allowed-request? request)) (md/success-deferred {:status 403 :body "Unauthorized"})
          (= :options method)              (md/success-deferred {:status 200 :headers (assoc headers "Access-Control-Allow-Headers" (str/join ", " (conj allowed-headers "X-PINGOTHER")) )})
          :else
          (md/chain' (handler request) (fn [response]
                                         (update response :headers (fnil into {}) (assoc headers "Access-Control-Allow-Headers" (str/join ", " allowed-headers) )))))))))
