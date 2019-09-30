(ns blaze.middleware.auth
  (:require
   [clojure.string :as string]
   [buddy.sign.jwt :as jwt]
   [cheshire.core :as json]
   #_[manifold.deferred :as md])
  (:import
   (java.util Base64 Date)
   (java.security KeyFactory)
   (java.security.spec X509EncodedKeySpec)))


;; TODO: Possibly make the expiration time configurable
(def expiration-minutes 60)


;; TODO: This url needs to be configurable, probably via an env var
(defn- keycloak-public-key []
  (-> "https://auth.breezeehr.com/auth/realms/patient-portal"
      slurp
      json/parse-string
      (get "public_key")))


(defn- public-key-atom-value [_]
  {:public-key (keycloak-public-key)
   :timestamp  (.getTime (Date.))})


(defn- public-key-str
  "When the public key atom is more than 1 hour old, update the public key.
   Always return the public key."
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
      ;; TODO: Figure out correct response. I was initially using nil because
      ;; that's what Geheimtur wanted.
      (prn "e.cause::" (:cause (Throwable->map e)))
      nil)))


;; TODO: Don't use a global state atom?
(def public-key-atom (atom (public-key-atom-value nil)))

(defn wrap-auth
  "Adds an Access-Control-Allow-Origin header with the value * to responses."
  [handler]
  (fn [request]
    (let [response     (handler request)
          auth-header  (get-in request [:headers "authorization"])
          bearer-token (string/replace auth-header "Bearer " "")
          token        (unsigned-token public-key-atom bearer-token)]
      ;; TODO: Insert the token into the response. Will possibly handle 403 here.
      response)))
