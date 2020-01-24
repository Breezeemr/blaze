(ns blaze.rest-api.middleware.cors
  (:require
   [manifold.deferred :as md]))

(def allowed-origins
  #{"https://localhost:8700"
    "http://localhost:8700"
    ;;TODO handle this: https://storage.googleapis.com/portal.breezeehr.com
    "https://portal.breezeehr.com"})

;;TODO this should be configurable
;:TODO needs to handle more then just origins
(defn allowed-request?
  [{{origin "origin"} :headers}]
  (allowed-origins origin))

(defn get-wrap-cors
  "Fills the traditional role of rejecting requests from sources we dont trust. Traditionally cors deals
  with just origins. But in our case, we need to be more specific e.g https://storage.googleapis.com/portal.breezeehr.com
  's domain isn't enough.

  Takes a function which will return a falsy value if the request is denied.
  "
  [allowed-request?]
  (fn [handler]
    (fn [{{origin "origin"} :headers
         :as               request}]
      (if-not (allowed-request? request)
        ;;NOTE There is no spec for a failed cors request response because traditionally its handled by
        ;;the client. So here we just return 403.
        {:status 403 :body "Unauthorized"}
        (md/chain' (handler request) #(update % :headers into {"Access-Control-Allow-Origin"  origin
                                                               "Access-Control-Allow-Methods" "GET, OPTIONS"
                                                               "Access-Control-Allow-Headers" "Accept, Content-Type, Authorization"
                                                               "Access-Control-Max-Age"       "3600"}))))))

