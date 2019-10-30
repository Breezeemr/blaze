(ns blaze.dev
  (:require
   [blaze.system :as system]
   [clojure.tools.reader.edn :as edn]
   [integrant.core :as ig]
   [integrant.repl :as ir]
   [datomic.api :as d]
   [reitit.ring :as rring]
   [reitit.core :as r]))

(defn- read-config []
  (-> "blaze.edn" slurp ig/read-string))




(comment
  (ig/load-namespaces (:config (read-config)))

  ;; prep can do stuff like load defaults (that you made)
  (ir/set-prep! #(:config (read-config)))

  ;;prep + initiate/state the system
  (ir/go)

  (def system (system/init! {:log/level "debug"}))

  ;;(def app (:blaze/rest-api system))

  ;;make a request
  ((:blaze/rest-api system)
   {:uri            "Condition"
    :params         {"category" "0"}
    :request-method :get})


  ;; if you make a change and want to see to propagate
  (ir/reset)

  ;;stop
  (ir/halt)

  

  ;; connect to in memory db and query patient
  (def conn (d/connect "datomic:mem://dev"))

  (def db (d/db conn))

  (d/q '[:find ?p ?id
         :where
         [?p :db/ident ?id]]
       db)

  ;; Look at routes
  (-> app (rring/get-router) (r/compiled-routes))



  (def router (rring/get-router app))

  (r/match-by-path router "http://localhost:8080/fhir/Patient"))
