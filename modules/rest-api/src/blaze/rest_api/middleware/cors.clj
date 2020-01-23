(ns blaze.rest-api.middleware.cors
  (:require
    [manifold.deferred :as md]
    [ring.util.response :as ring]))

(defn wrap-cors
  [handler]
  ;;TODO pull allowed origins from a config
  ;;Have the configs depend on environment: e.g development should allow for all of these but produciton only the top two.
  (let [allowed-origin? #{"https://localhost:8700"
                          "http://localhost:8700"
                          "https://storage.googleapis.com/portal.breezeehr.com"
                          "https://portal.breezeehr.com"}]
    (fn [{{origin "origin"} :headers
         :as               request}]
      (-> (handler request)
        (md/chain' #(-> %
                      ;;TODO in the case when the origin doesn't pass the whitelist its not clear (atm) how to convey that their were
                      ;;multiple other origin options. So lets just send back one randomly for now and think about how to improve this.
                      (assoc-in [:headers "Access-Control-Allow-Origin"] (or (allowed-origin? origin) (first allowed-origin?)))
                      (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, OPTIONS")
                      (assoc-in [:headers "Access-Control-Allow-Headers"] "Accept, Content-Type, Authorization")
                      (assoc-in [:headers "Access-Control-Max-Age"] "3600")))))))
