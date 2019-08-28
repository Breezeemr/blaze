(ns blaze.fhir.operation.evaluate-measure.handler.impl-test
  (:require
    [blaze.datomic.test-util :as datomic-test-util]
    [blaze.executors :as ex]
    [blaze.fhir.operation.evaluate-measure.handler.impl :refer [handler]]
    [blaze.fhir.operation.evaluate-measure.measure-test :as measure-test]
    [blaze.fhir.response.create-test :as create-test]
    [blaze.handler.fhir.test-util :as fhir-test-util]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.test :refer :all]
    [reitit.core :as reitit]
    [taoensso.timbre :as log])
  (:import
    [java.time Clock Instant ZoneOffset OffsetDateTime]))


(defn fixture [f]
  (st/instrument)
  (st/instrument
    [`handler]
    {:spec
     {`handler
      (s/fspec
        :args (s/cat :clock #(instance? Clock %)
                     :conn #{::conn}
                     :term-service #{::term-service}
                     :executor ex/executor?))}})
  (datomic-test-util/stub-db ::conn ::db)
  (log/with-merged-config {:level :error} (f))
  (st/unstrument))


(use-fixtures :each fixture)


(def base-uri "http://localhost:8080")
(def clock (Clock/fixed Instant/EPOCH (ZoneOffset/ofHours 0)))
(def now (OffsetDateTime/ofInstant Instant/EPOCH (ZoneOffset/ofHours 0)))
(defonce executor (ex/single-thread-executor))


(deftest handler-test
  (testing "Returns Not Found on Non-Existing Measure"
    (datomic-test-util/stub-resource ::db #{"Measure"} #{"0"} nil?)

    (let [{:keys [status body]}
          ((handler clock ::conn ::term-service executor)
           {:path-params {:type "Measure" :id "0"}})]

      (is (= 404 status))

      (is (= "OperationOutcome" (:resourceType body)))

      (is (= "error" (-> body :issue first :severity)))

      (is (= "not-found" (-> body :issue first :code)))))


  (testing "Returns Gone on Deleted Resource"
    (datomic-test-util/stub-resource ::db #{"Measure"} #{"0"} #{::measure})
    (datomic-test-util/stub-deleted? ::measure true?)

    (let [{:keys [status body]}
          ((handler clock ::conn ::term-service executor)
           {:path-params {:type "Measure" :id "0"}})]

      (is (= 410 status))

      (is (= "OperationOutcome" (:resourceType body)))

      (is (= "error" (-> body :issue first :severity)))

      (is (= "deleted" (-> body :issue first :code)))))


  (testing "Success"
    (datomic-test-util/stub-resource ::db #{"Measure"} #{"0"} #{::measure})
    (datomic-test-util/stub-deleted? ::measure false?)

    (testing "as GET request"
      (measure-test/stub-evaluate-measure now ::db "2014" "2015" ::measure ::measure-report)

      (let [{:keys [status body]}
            @((handler clock ::conn ::term-service executor)
              {::reitit/router ::router
               :request-method :get
               :path-params {:type "Measure" :id "0"}
               :query-params
               {"periodStart" "2014"
                "periodEnd" "2015"}})]

        (is (= 200 status))

        (is (= ::measure-report body))))

    (testing "as POST request"
      (measure-test/stub-evaluate-measure now ::db "2014" "2015" ::measure {})
      (datomic-test-util/stub-squuid "0")
      (fhir-test-util/stub-upsert-resource ::conn ::term-service ::db :server-assigned-id {"id" "0"} {:db-after ::db-after})
      (create-test/stub-build-created-response ::router nil? ::db-after "MeasureReport" "0" ::response)

      (is (= ::response
             @((handler clock ::conn ::term-service executor)
               {::reitit/router ::router
                :request-method :post
                :path-params {:type "Measure" :id "0"}
                :query-params
                {"periodStart" "2014"
                 "periodEnd" "2015"}}))))))
