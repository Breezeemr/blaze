(ns blaze.middleware.authentication
  "Ring middleware that verifies a signed JWT using OpenID Connect
   to provide the public key used to sign the token. The public key
   is stored in an atom that is updated every `expiration-minutes`."
  (:require
   [clojure.string :as string]
   [buddy.core.keys :as keys]
   [buddy.sign.jwt :as jwt]
   [cheshire.core :as json]
   [ring.util.response :as ring])
  (:import
   (java.util Date)))


(defn- public-key
  "From the OpenID Configuration url, follow the `jwks_uri` to get the
   first jwk."
  [url]
  (-> url
      slurp
      json/parse-string
      (get "jwks_uri")
      slurp
      (json/parse-string keyword)
      :keys ;; Buddy expects keywords, not strings
      first ;; TODO: Handle missing key?
      keys/jwk->public-key))


(defn public-key-atom-value [v]
  {:public-key         (public-key (:openid-url v))
   :timestamp          (.getTime (Date.))
   :openid-url         (:openid-url v)
   :expiration-minutes 60})


(defn- updated-public-key-atom
  "When the public key atom is more than `expiration-minutes` old,
   update the public key. Always return the public key."
  [public-key-atom]
  (do
    (when (< (* (:expiration-minutes @public-key-atom) 60000)
             (- (.getTime (Date.)) (:timestamp @public-key-atom)))
      (swap! public-key-atom public-key-atom-value))
    public-key-atom))


(defn- unsigned-token
  [public-key-atom signed-token]
  (try
    (let [public-key (:public-key @(updated-public-key-atom public-key-atom))]
      (jwt/unsign signed-token public-key {:alg :rs256}))
    (catch Exception e
      (:cause (Throwable->map e)))))


(defn- unauthenticated-response [message]
  (-> (ring/response {:message message})
      (ring/status 403)))


(defn wrap-authentication
  "If successful process request, else respond with 403. Update the
  public key when necessary."
  [public-key-atom]
  (fn [handler]
    (fn [request]
      ;; If there is no atom, skip authorization. Otherwise, continue authorization.
      (if (nil? public-key-atom)
        (handler request)
        (let [auth-header (get-in request [:headers "authorization"])]
          ;; If auth header is nil or empty, inform client. Otherwise, continue authorization.
          (if (string/blank? auth-header)
            (unauthenticated-response "Missing authorization header")
            (let [bearer-token (string/replace auth-header "Bearer " "")
                  token        (unsigned-token public-key-atom bearer-token)]
              ;; If token is a string, it is actually the error message. Otherwise, authorization succeeded.
              (if (string? token)
                (unauthenticated-response "Forbidden")
                (handler request)))))))))
