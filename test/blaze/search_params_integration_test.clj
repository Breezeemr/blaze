(ns blaze.search-params-integration-test
  (:require
   [cheshire.core :as json]
   [clojure.spec.test.alpha :as st]
   [blaze.handler.fhir.search :refer [handler]]
   [blaze.handler.fhir.test-util :as fhir-test-util]
   [clojure.test :refer :all]
   [datomic.api :as d]
   [datomic-spec.test :as dst]
   [blaze.bundle :as bundle]
   [blaze.cql-translator :as cql]
   [blaze.datomic.test-util :as test-util]
   [blaze.elm.compiler :as compiler]
   [blaze.elm.spec]
   [blaze.elm.evaluator :as evaluator]
   [blaze.terminology-service.extern :as ts]
   [reitit.core :as reitit]
   [taoensso.timbre :as log])
  (:import
   [java.time OffsetDateTime Year]))

(defonce conn (test-util/connect))
(defonce db (d/db (st/with-instrument-disabled conn)))

(defn fixture [f]
  (st/instrument)
  (dst/instrument)
  (log/with-merged-config {:level :error} (f))
  (st/unstrument))

(use-fixtures :each fixture)

(def term-service
  (ts/term-service "http://tx.fhir.org/r4" {} nil nil))

;;TODO improve transaction update so its more uniform. Or at least
;; understand why were making two separate transactions

(defn db-with [{:strs [entries]}]
  (let [entries @(bundle/annotate-codes term-service db entries)
        db      (:db-after @(d/transact conn (bundle/code-tx-data db entries)))]
    (d/transact conn (bundle/tx-data db entries))))

(defn- evaluate [db query]
  @(evaluator/evaluate db (OffsetDateTime/now)
                       (compiler/compile-library db (cql/translate query) {})))

(defn read-data [query-name]
  (-> (slurp (str "integration-test/" query-name "/data.json"))
      (json/parse-string)))

;;TODO check if it makes sense to store query's for these tests here.
;;TODO generalize test data
(def patient-with-condition (read-data "query-8"))
(def patient patient-with-condition)

;; TODO reference test plan. it may or may make sense to test these individually.
;; 0. Conditions              [x]
;; 1. MedicationRequest       [x] https://www.hl7.org/fhir/medicationrequest-examples.html
;; 2. AllergyIntolerance       [] https://www.hl7.org/fhir/allergyintolerance-examples.html
;; 3. UDI?                     [] https://www.hl7.org/fhir/DSTU2/device-examples.html
;; 4. goals                    [] https://www.hl7.org/fhir/goal.html
;; 5. procedures               [] https://www.hl7.org/fhir/procedure-examples.html
;; 6. immunization             []  https://www.hl7.org/fhir/immunization-example.html
;; 7. service equest/lab tests [] https://www.hl7.org/fhir/servicerequest.html
;; 8. Consent
;; 9. Observation
;; 10. CarePlan

;; NOTE these test stub the router, which ideally they wouldn't
;; as that's a key component in what were testing.

(deftest search-params
  (testing "Given a Patient, when a server gets a FHIR search query"
    (testing "with a reference parameter as described https://www.hl7.org/fhir/search.html#reference"
      (testing "supporting lookup via logical id"
        (testing "Condition"
          (testing "via patient"
            (let [resource        "Condition"
                  reference-param "patient"
                  logical-id      "0"
                  search-params   {reference-param logical-id}]
              (db-with patient-with-condition)
              (fhir-test-util/stub-instance-url ::router resource logical-id ::full-url)
              (let [{:keys [status body]} @((handler conn)
                                            {:path-params    {:type resource}
                                             ::reitit/router ::router
                                             :params         search-params})
                    returned-resource-ids (into #{}
                                                (->> body
                                                     :entry
                                                     (map #(get-in % [:resource "id"]))))]


                (is (= 200 status))
                (is (= #{logical-id} returned-resource-ids )))))
          (testing "via subject"
            (let [resource        "Condition"
                  reference-param "subject"
                  logical-id      "0"
                  search-params   {reference-param logical-id}]
              (db-with patient-with-condition)
              (fhir-test-util/stub-instance-url ::router resource logical-id ::full-url)
              (let [{:keys [status body]} @((handler conn)
                                            {:path-params    {:type resource}
                                             ::reitit/router ::router
                                             :params         search-params})
                    returned-resource-ids (into #{}
                                                (->> body
                                                     :entry
                                                     (map #(get-in % [:resource "id"]))))]


                (is (= 200 status))
                (is (= #{logical-id} returned-resource-ids ))))))
        (testing "MedicationRequest"
          (let [resource        "MedicationRequest"
                reference-param "patient"
                logical-id      "0"
                search-params   {reference-param logical-id}]
            (db-with patient)
            (fhir-test-util/stub-instance-url ::router resource logical-id ::full-url)
            (let [{:keys [status body]} @((handler conn)
                                          {:path-params    {:type resource}
                                           ::reitit/router ::router
                                           :params         search-params})
                  returned-resource-ids (into #{}
                                              (->> body
                                                   :entry
                                                   (map #(get-in % [:resource "id"]))))]

              (is (= 200 status))
              (is (= #{logical-id} returned-resource-ids )))))))
    (testing "with a token type parameter as described here https://www.hl7.org/fhir/search.html#token"
      (testing "in Condition"
        (testing "supporting `category` which is a codeable concept"
          (let [resource      "Condition"
                paramater     "category"
                category      "urgent"
                search-params {paramater category}]
            (db-with patient-with-condition)
            (fhir-test-util/stub-instance-url ::router resource "0" ::full-url)
            (let [{:keys [status body]} @((handler conn)
                                          {:path-params    {:type resource}
                                           ::reitit/router ::router
                                           :params         search-params})]

              ;; TODO replace first calls with some other more generic method
              (is (= category (-> body
                                  :entry
                                  first
                                  :resource
                                  (get "category")
                                  first
                                  (get "coding")
                                  first
                                  (get "code"))))

              (is (= 200 status))))))
      (testing "in Observation"
        (testing "supporting `patient` and `category`"
          (let [resource      "Observation"
                paramater     "category"
                category      "vital-signs"
                search-params {paramater category
                               "patient" "0"}]
            (db-with patient-with-condition)
            ;;TODO is this stub useful for this test?
            (fhir-test-util/stub-instance-url ::router resource "0" ::full-url)
            (let [{:keys [status body]} @((handler conn)
                                          {:path-params    {:type resource}
                                           ::reitit/router ::router
                                           :params         search-params})]

              (is (= category (-> body
                                  :entry
                                  first
                                  :resource
                                  (get "category")
                                  first
                                  (get "coding")
                                  first
                                  (get "code"))))

              (is (= 200 status)))))))))

