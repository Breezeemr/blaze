(ns dromon.server
  "HTTP Server

  Call `init!` to initialize an HTTP server and `shutdown!` to release its port
  again."
  (:require [aleph.http :as http]
            [clojure.spec.alpha :as s]
            [dromon.executors :as ex]
            [manifold.deferred :as md]
            [ring.util.response :as ring])
  (:import java.io.Closeable))

(s/def ::port
  (s/and nat-int? #(<= % 65535)))

(s/def ::executor ex/executor?)
(s/def ::handler fn?)
(s/def ::version string?)
(s/def ::ssl-context any?)
(s/def ::config (s/keys
                  :req-un [::port ::executor ::handler ::version]
                  :opt-un [::ssl-context]))


(defn- wrap-server [handler server]
  (fn [request]
    (-> (handler request)
        (md/chain' #(ring/header % "Server" server)))))

(s/fdef init! :args (s/cat :config ::config))

(defn init!
  "Creates a new HTTP server listening on `port` serving from `handler`.

  Call `shutdown!` on the returned server to stop listening and releasing its
  port."
  [{:keys [handler version] :as config}]
  (http/start-server
    (wrap-server handler (str "Dromon/" version))
    config))

(s/fdef shutdown!
  :args (s/cat :server #(instance? Closeable %)))

(defn shutdown!
  "Shuts `server` down, releasing its port."
  [server]
  (.close ^Closeable server))
