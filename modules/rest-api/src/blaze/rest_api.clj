(ns blaze.rest-api
  (:require
    [blaze.rest-api.spec]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [datomic-spec.core :as ds]
    [integrant.core :as ig]
    [reitit.ring]
    [reitit.ring.spec]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]
    [reitit.core :as reitit]))


(defn resolve-pattern
  {:arglists '([resource-patterns structure-definition])}
  [resource-patterns {:keys [name]}]
  (some
    #(when (= name (:blaze.rest-api.resource-pattern/type %)) %)
    resource-patterns))


(defn resource-route
  "Route data contains resource type."
  {:arglists '([resource-patterns structure-definition])}
  [resource-patterns {:keys [name] :as structure-definition}]
  (when-let
    [{:blaze.rest-api.resource-pattern/keys [interactions operations]}
     (resolve-pattern resource-patterns structure-definition)]
    (into
      [(str "/" name)
       {:middleware []
        :fhir.resource/type name}
       [""
        (cond-> {:name (keyword name "type")}
          (contains? interactions :search-type)
          (assoc :get (-> interactions :search-type
                          :blaze.rest-api.interaction/handler))
          (contains? interactions :create)
          (assoc :post (-> interactions :create
                           :blaze.rest-api.interaction/handler)))]
       ["/_history"
        (cond-> {}
          (contains? interactions :history-type)
          (assoc :get (-> interactions :history-type
                          :blaze.rest-api.interaction/handler)))]
       ["/_search"
        (cond-> {}
          (contains? interactions :search-type)
          (assoc :post (-> interactions :search-type
                           :blaze.rest-api.interaction/handler)))]
       ["/{id}"
        [""
         (cond-> {:name (keyword name "instance")}
           (contains? interactions :read)
           (assoc :get (-> interactions :read
                           :blaze.rest-api.interaction/handler))
           (contains? interactions :update)
           (assoc :put (-> interactions :update
                           :blaze.rest-api.interaction/handler))
           (contains? interactions :delete)
           (assoc :delete (-> interactions :delete
                              :blaze.rest-api.interaction/handler)))]
        ["/_history"
         [""
          (cond-> {:name (keyword name "history-instance")}
            (contains? interactions :history-instance)
            (assoc :get (-> interactions :history-instance
                            :blaze.rest-api.interaction/handler)))]
         ["/{vid}"
          (cond-> {:name (keyword name "versioned-instance")}
            (contains? interactions :vread)
            (assoc :get (-> interactions :vread
                            :blaze.rest-api.interaction/handler)))]]]]
      operations)))


(comment
  (require '[reitit.core :as reitit])
  (require '[reitit.ring.spec])
  (resource-route
    [#:blaze.rest-api.resource-pattern
        {:type "Patient"
         :interactions
         {:read
          {:blaze.rest-api.interaction/handler (fn [_] "read")}
          :search-type
          {:blaze.rest-api.interaction/handler (fn [_] "search-type")}}}]
    {:name "Patient"})
  (def router (reitit/router *1))
  (def ring-handler
    (reitit.ring/ring-handler
      (reitit.ring/router
        *1
        {:syntax :bracket
         :validate reitit.ring.spec/validate
         :conflicts nil})))
  (reitit/match-by-path router "Patient")
  (ring-handler {:uri "Patient" :request-method :get})
  (def base-url "foo")
  (def context-path "x")
  )


(defn- join-path [a b]
  (cond-> a (not (str/blank? b)) (str "/" b)))


(defn router
  [base-url
   structure-definitions
   capabilities-handler
   {:blaze.rest-api/keys
    [context-path
     transaction-handler
     history-system-handler
     resource-patterns]
    :or
    {context-path ""}}]
  (reitit.ring/router
    (into
      [(cond->> context-path (not (str/blank? context-path)) (str "/"))
       {:blaze/base-url (join-path base-url context-path)}
       [""
        (cond-> {:middleware []}
          (some? transaction-handler)
          (assoc :post transaction-handler))]
       ["/metadata"
        {:get capabilities-handler}]
       ["/_history"
        (cond-> {:middleware []}
          (some? history-system-handler)
          (assoc :get history-system-handler))]]
      (comp
        (filter #(= "resource" (:kind %)))
        (remove :experimental)
        (remove :abstract)
        (map #(resource-route resource-patterns %))
        (remove nil?))
      structure-definitions)
    {:syntax :bracket
     :conflicts nil
     :validate reitit.ring.spec/validate
     :reitit.ring/default-options-handler
     (fn [_]
       (-> (ring/response nil)
           (ring/status 405)))}))


(defn capabilities-handler
  [structure-definitions config]
  (fn [_]))


(comment
  (require 'blaze.structure-definition)
  (reitit.core/routes
    (router
      "foo"
      (blaze.structure-definition/read-structure-definitions)
      (fn [_])
      #:blaze.rest-api
          {:resource-patterns
           [#:blaze.rest-api.resource-pattern
               {:type "Patient"
                :interactions
                {:read
                 {:blaze.rest-api.interaction/handler (fn [_] "read")}
                 :search-type
                 {:blaze.rest-api.interaction/handler (fn [_] "search-type")}}}]}))
  )


(def ^:private default-handler
  (reitit.ring/create-default-handler
    {:not-found
     (fn [_]
       (ring/not-found
         {:resourceType "OperationOutcome"
          :issue
          [{:severity "information"
            :code "not-found"}]}))
     :method-not-allowed
     (fn [_]
       (-> (ring/response
             {:resourceType "OperationOutcome"
              :issue
              [{:severity "information"
                :code "not-found"}]})
           (ring/status 405)))
     :not-acceptable
     (fn [_]
       (prn :not-acceptable)
       (-> (ring/response
             {:resourceType "OperationOutcome"
              :issue
              [{:severity "error"
                :code "structure"}]})
           (ring/status 406)))}))


(s/fdef handler
  :args
  (s/cat
    :base-url string?
    :conn ::ds/conn
    :structure-definitions (s/coll-of :fhir.un/StructureDefinition)
    :config :blaze/rest-api))

(defn handler
  "Whole app Ring handler."
  [base-url conn structure-definitions config]
  (clojure.pprint/pprint
    (reitit/routes
      (router
        base-url
        structure-definitions
        (capabilities-handler structure-definitions config)
        config)))
  (reitit.ring/ring-handler
    (router
      base-url
      structure-definitions
      (capabilities-handler structure-definitions config)
      config)
    default-handler))


(defmethod ig/init-key :blaze/rest-api
  [_
   {:keys [base-url structure-definitions]
    {:blaze.rest-api/keys [context-path]
     :or {context-path ""}
     :as config}
    :config
    :database/keys [conn]}]
  (log/info
    "Init FHIR RESTful API with base URL:" (join-path base-url context-path))
  (handler base-url conn structure-definitions config))
