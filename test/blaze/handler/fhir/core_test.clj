(ns blaze.handler.fhir.core-test
  (:require
    [blaze.handler.fhir.core :refer [handler router]]
    [blaze.middleware.fhir.type :refer [wrap-type]]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.test :refer :all]
    [reitit.core :as reitit]
    [reitit.ring :as reitit-ring]
    [taoensso.timbre :as log]))


(defn fixture [f]
  (st/instrument)
  (st/instrument
    [`handler]
    {:spec
     {`handler
      (s/fspec
        :args (s/cat :base-url #{::base-url} :conn #{::conn} :handlers map?))}})
  (st/instrument
    [`wrap-type]
    {:spec
     {`wrap-type
      (s/fspec
        :args (s/cat :handler fn? :conn #{::conn}))}
     :replace
     {`wrap-type
      (fn [handler _]
        handler)}})
  (log/with-merged-config {:level :fatal} (f))
  (st/unstrument))


(use-fixtures :each fixture)


(def ^:private handlers
  {:handler.fhir/capabilities (fn [_] ::fhir-capabilities-handler)
   :handler.fhir/create (fn [_] ::fhir-create-handler)
   :handler.fhir/delete (fn [_] ::fhir-delete-handler)
   :handler.fhir/history-instance (fn [_] ::fhir-history-instance-handler)
   :handler.fhir/history-type (fn [_] ::fhir-history-type-handler)
   :handler.fhir/history-system (fn [_] ::fhir-history-system-handler)
   :handler.fhir/read (fn [_] ::fhir-read-handler)
   :handler.fhir/search (fn [_] ::fhir-search-handler)
   :handler.fhir/transaction (fn [_] ::fhir-transaction-handler)
   :handler.fhir/update (fn [_] ::fhir-update-handler)
   :handler.fhir.operation/evaluate-measure
   (fn [_] ::fhir-operation-evaluate-measure-handler)})


(defn test-handler []
  (reitit-ring/ring-handler (router ::base-url ::conn handlers)))


(defn- match [path request-method]
  ((test-handler) {:request-method request-method :uri path}))


(deftest router-test
  (are [path request-method handler] (= handler (match path request-method))
    "Patient" :get ::fhir-search-handler
    "Patient" :post ::fhir-create-handler
    "Patient/_history" :get ::fhir-history-type-handler
    "Patient/_search" :post ::fhir-search-handler
    "Patient/0" :get ::fhir-read-handler
    "Patient/0" :put ::fhir-update-handler
    "Patient/0" :delete ::fhir-delete-handler
    "Patient/0/_history" :get ::fhir-history-instance-handler
    "Patient/0/_history/42" :get ::fhir-read-handler
    "Measure/0/$evaluate-measure" :get ::fhir-operation-evaluate-measure-handler
    "Measure/0/$evaluate-measure" :post ::fhir-operation-evaluate-measure-handler))


(deftest router-match-by-name-test
  (let [router (router ::base-url ::conn handlers)]
    (are [name params path]
      (= (reitit/match->path (reitit/match-by-name router name params)) path)

      :fhir/type
      {:type "Patient"}
      "Patient"

      :fhir/instance
      {:type "Patient" :id "23"}
      "Patient/23"

      :fhir/versioned-instance
      {:type "Patient" :id "23" :vid "42"}
      "Patient/23/_history/42")))
