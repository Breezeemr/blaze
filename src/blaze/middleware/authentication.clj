(ns blaze.middleware.authentication
  (:require
   [clojure.string :as string]
   [buddy.sign.jwt :as jwt]
   [cheshire.core :as json]
   [ring.util.response :as ring]
   #_[manifold.deferred :as md])
  (:import
   (java.util Base64 Date)
   (java.security KeyFactory)
   (java.security.spec X509EncodedKeySpec)))

;; https://auth.breezeehr.com/auth/realms/patient-portal/.well-known/openid-configuration


(def ^:private expiration-minutes 1)


;; TODO: Acutally use proper OpenID methods
(defn- get-public-key [url]
  (-> url
      slurp
      json/parse-string
      (get "issuer")
      slurp
      json/parse-string
      (get "public_key")))


;; TODO: Figure out if I can do this with only one argument
(defn public-key-atom-value [v]
  {:public-key (get-public-key (:openid-url v))
   :timestamp  (.getTime (Date.))
   :openid-url (:openid-url v)})


(defn- public-key-str
  "When the public key atom is more than `expiration-minutes` old,
   update the public key. Always return the public key."
  [public-key-atom]
  (do
    (when (< (* expiration-minutes 60000)
             (- (.getTime (Date.)) (:timestamp @public-key-atom)))
      (swap! public-key-atom public-key-atom-value))
    (:public-key @public-key-atom)))


(defn- str->public-key [s]
  (->> s
       #^bytes .getBytes
       (.decode (Base64/getDecoder))
       X509EncodedKeySpec.
       (.generatePublic (KeyFactory/getInstance "RSA"))))


(defn- unsigned-token [public-key-atom signed-token]
  (try
    (let [public-key (str->public-key (public-key-str public-key-atom))]
      (jwt/unsign signed-token public-key {:alg :rs256}))
    (catch Exception e
      ;; Return nil to signal failure to unsign token
      (:cause (Throwable->map e)))))


(defn unauthenticated-response [message]
  (-> (ring/response {:message message})
      (ring/status 403)))


(defn wrap-authentication
  "If successful process request, else respond with 403. Update the
  public key when necessary."
  [public-key-atom]
  (fn [handler]
    (fn [request]
      (if (nil? public-key-atom) ;; If there is no atom, skip authorization
        (handler request)
        (let [auth-header (get-in request [:headers "authorization"])]
          (if (string/blank? auth-header) ;; If auth header is nil or empty, inform client
            (unauthenticated-response "Missing authorization header")
            (let [bearer-token    (string/replace auth-header "Bearer " "")
                  token           (unsigned-token public-key-atom bearer-token)]
              (if (string? token) ;; If token is a string, it really is an error message, pass along to client (TODO: Is this too much info to expose?)
                (unauthenticated-response token)
                (handler request)))))))))
