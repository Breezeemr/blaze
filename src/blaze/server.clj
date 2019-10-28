(ns blaze.server
  "HTTP Server

  Call `init!` to initialize an HTTP server and `shutdown!` to release its port
  again."
  (:require
    [aleph.http :as http]
    [blaze.executors :as ex]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [manifold.deferred :as md]
    [ring.util.response :as ring])
  (:import
    [java.io Closeable]))


(s/def ::port
  (s/and nat-int? #(<= % 65535)))


(defn- remove-context-path
  "A temporary hack to remove the context path
   from the request uri."
  [request context-path]
  (update request :uri #(str/replace % (re-pattern (str "/" context-path "/")) "")))


(defn- wrap-server [handler server context-path]
  (fn [request]
    (-> (handler (remove-context-path request context-path))
        (md/chain' #(ring/header % "Server" server)))))


(s/fdef init!
  :args (s/cat :port ::port :executor ex/executor? :handler fn?
               :version string?))

(defn init!
  "Creates a new HTTP server listening on `port` serving from `handler`.

  Call `shutdown!` on the returned server to stop listening and releasing its
  port."
  [port executor handler version context-path]
  (http/start-server
   (wrap-server handler (str "Blaze/" version) context-path)
    {:port port :executor executor}))


(s/fdef shutdown!
  :args (s/cat :server #(instance? Closeable %)))

(defn shutdown!
  "Shuts `server` down, releasing its port."
  [server]
  (.close ^Closeable server))
