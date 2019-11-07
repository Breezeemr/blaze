(ns blaze.rest-api.middleware.cors
  (:require
    [manifold.deferred :as md]
    [ring.util.response :as ring]))

(defn wrap-cors
  [handler]
  (fn [request]
    (-> (handler request)
        (md/chain' #(-> %
                        (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
                        (assoc-in [:headers "Access-Control-Allow-Headers"] "Accept, Content-Type, Authorization")
                        (assoc-in [:headers "Access-Control-Max-Age"] "3600"))))))
