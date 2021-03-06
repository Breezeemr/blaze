(ns blaze.terminology-service.extern
  (:require
    [aleph.http :as http]
    [aleph.http.client-middleware :as client-middleware]
    [blaze.executors :refer [executor?]]
    [blaze.fhir-client :as client]
    [blaze.terminology-service :as ts :refer [term-service?]]
    [clojure.core.cache :as cache]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [manifold.deferred :as md]
    [prometheus.alpha :as prom :refer [defcounter defhistogram]]
    [taoensso.timbre :as log])
  (:import
    [java.util.concurrent TimeoutException]))


(def ^:private cache (atom (cache/lru-cache-factory {} :threshold 1024)))


(defcounter errors-total
  "Total number of Terminology Service errors."
  {:namespace "terminology_service"}
  "type")


(defhistogram request-duration-seconds
  "Terminology Service request latencies in seconds."
  {:namespace "terminology_service"}
  (take 14 (iterate #(* 2 %) 0.001)))


(defn- wrap-error-handling [client]
  (fn [req]
    (-> (client req)
        (md/catch'
          TimeoutException
          (fn [e]
            (prom/inc! errors-total "timeout")
            (log/warn (ex-message e))
            (md/error-deferred
              {::anom/category ::anom/busy
               ::anom/message (ex-message e)}))))))


(defn- wrap-request-timing [client]
  (fn [req]
    (let [timer (prom/timer request-duration-seconds)]
      (-> (client req)
          (md/finally' #(prom/observe-duration! timer))))))


(defn- connection-pool [proxy-options]
  (http/connection-pool
    (cond->
      {:middleware
       (fn [client]
         (let [client' (-> client
                           wrap-request-timing
                           wrap-error-handling)]
           (fn [req]
             ;; skip middleware on closing requests
             (if (:aleph.http.client/close req)
               (client req)
               (-> req
                   client-middleware/wrap-url
                   client-middleware/wrap-query-params
                   client')))))}

      ;; It's important to disable DNS resolving when in proxy mode, because
      ;; first it's not necessary and second domain names which are resolvable
      ;; by the proxy might not resolvable locally. Can be removed when
      ;; https://github.com/ztellman/aleph/issues/522 is solved.
      (seq proxy-options)
      (update :connection-options assoc :proxy-options
              proxy-options :name-resolver :noop))))


(defn- expand-value-set [base opts params]
  (log/debug "Expand ValueSet with params" (pr-str params))
  (client/fetch (str base "/ValueSet/$expand")
                (assoc opts :query-params params)))


(defn- hit [cache k]
  (if (cache/has? cache k)
    (cache/hit cache k)
    cache))


(defrecord TermService [base opts]
  ts/TermService
  (-expand-value-set [_ params]
    (if-let [res (get (swap! cache hit params) params)]
      res
      (-> (expand-value-set base opts params)
          (md/chain'
            (fn [value-set]
              (swap! cache cache/miss params value-set)
              value-set))))))


(defn- opts [proxy-options connection-timeout request-timeout]
  {:pool (connection-pool proxy-options)
   :connection-timeout (or connection-timeout 5000)
   :request-timeout (or request-timeout 30000)})


(s/def :proxy-options/host
  string?)


(s/def :proxy-options/port
  pos-int?)


(s/def :proxy-options/user
  string?)


(s/def :proxy-options/password
  string?)


(s/def ::proxy-options
  (s/keys :opt-un [:proxy-options/host :proxy-options/port
                   :proxy-options/user :proxy-options/password]))


(s/def ::milli-second
  pos-int?)


(s/fdef term-service
  :args (s/cat :base string? :proxy-options ::proxy-options
               :connection-timeout (s/nilable ::milli-second)
               :request-timeout (s/nilable ::milli-second))
  :ret term-service?)

(defn term-service [base proxy-options connection-timeout request-timeout]
  (->TermService base (opts proxy-options connection-timeout request-timeout)))
