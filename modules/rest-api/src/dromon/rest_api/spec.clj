(ns dromon.rest-api.spec
  (:require
   [dromon.spec]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [buddy.auth.protocols :refer [IAuthentication]]))

(s/def :dromon.rest-api/auth-backends
  (s/coll-of #(satisfies? IAuthentication %)))


(s/def :dromon.rest-api/transaction-handler
  fn?)


(s/def :dromon.rest-api/history-system-handler
  fn?)


(s/def :dromon.rest-api.resource-pattern/type
  (s/or :name string? :default #{:default}))


(def interaction-code?
  #{:read
    :vread
    :update
    :patch
    :delete
    :history-instance
    :history-type
    :create
    :search-type})


(s/def :dromon.rest-api.interaction/handler
  (s/or :ref ig/ref? :handler fn?))


(s/def :dromon.rest-api.interaction/doc
  string?)


(s/def :dromon.rest-api/interaction
  (s/keys
    :req
    [:dromon.rest-api.interaction/handler]
    :opt
    [:dromon.rest-api.interaction/doc]))


;; Interactions keyed there code
(s/def :dromon.rest-api.resource-pattern/interactions
  (s/map-of interaction-code? :dromon.rest-api/interaction))


(s/def :dromon.rest-api/resource-pattern
  (s/keys
    :req
    [:dromon.rest-api.resource-pattern/type
     :dromon.rest-api.resource-pattern/interactions]))


(s/def :dromon.rest-api/resource-patterns
  (s/coll-of :dromon.rest-api/resource-pattern))


(s/def :dromon.rest-api/version
  string?)


(s/def :dromon.rest-api/context-path
  string?)


(s/def :dromon.rest-api.operation/code
  string?)


(s/def :dromon.rest-api.operation/def-uri
  string?)


(s/def :dromon.rest-api.operation/resource-types
  (s/coll-of string?))


(s/def :dromon.rest-api.operation/system-handler
  (s/or :ref ig/ref? :handler fn?))


(s/def :dromon.rest-api.operation/type-handler
  (s/or :ref ig/ref? :handler fn?))


(s/def :dromon.rest-api.operation/instance-handler
  (s/or :ref ig/ref? :handler fn?))


(s/def :dromon.rest-api/operation
  (s/keys
    :req
    [:dromon.rest-api.operation/code
     :dromon.rest-api.operation/def-uri
     :dromon.rest-api.operation/resource-types]
    :opt
    [:dromon.rest-api.operation/system-handler
     :dromon.rest-api.operation/type-handler
     :dromon.rest-api.operation/instance-handler]))


(s/def :dromon.rest-api/operations
  (s/coll-of :dromon.rest-api/operation))
