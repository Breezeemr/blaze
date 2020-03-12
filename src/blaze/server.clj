(ns blaze.server
  "HTTP Server

  Call `init!` to initialize an HTTP server and `shutdown!` to release its port
  again."
  (:require
    [aleph.http :as http]
    [blaze.executors :as ex]
    [clojure.spec.alpha :as s]
    [manifold.deferred :as md]
    [ring.util.response :as ring])
  (:import
   [java.io Closeable]
   [io.netty.handler.ssl SslContextBuilder]))


(s/def ::port
  (s/and nat-int? #(<= % 65535)))


(defn- wrap-server [handler server]
  (fn [request]
    (-> (handler request)
        (md/chain' #(ring/header % "Server" server)))))


(s/fdef init!
  :args (s/cat :port ::port :executor ex/executor? :handler fn?
               :version string?))

(defn init!
  "Creates a new HTTP server listening on `port` serving from `handler`.

  Call `shutdown!` on the returned server to stop listening and releasing its
  port."
  [port executor handler version]
  (cheshire.generate/add-encoder java.net.URI cheshire.generate/encode-str)
  (http/start-server
    (wrap-server handler (str "Blaze/" version))
    {:port port
     :executor executor
     ;;:ssl-context "TODO needs to be a `io.netty.handler.ssl.SslContext see https://netty.io/4.1/api/io/netty/handler/ssl/SslContextBuilder.html
     ;; something like (.forServer SslContextBuilder ...) Not sure what arguments it should take looks like a lot of options."
     }))


(s/fdef shutdown!
  :args (s/cat :server #(instance? Closeable %)))

(defn shutdown!
  "Shuts `server` down, releasing its port."
  [server]
  (.close ^Closeable server))
