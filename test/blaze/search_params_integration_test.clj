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

(def patient-with-condition (read-data "query-8"))

(deftest search-params
  (testing "Given a Patient with Condition, when a server gets a FHIR search query"
    (testing "with a reference parameter as described https://www.hl7.org/fhir/search.html#reference"
      (testing "supporting lookup via logical id"
        (let [resource        "Condition"
              reference-param "subject"
              logical-id      "0"
              search-params   {reference-param logical-id}]
          ;; load data into db and stub router
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
            (is (contains? returned-resource-ids logical-id))))))
    (testing "with a token type parameter as described here https://www.hl7.org/fhir/search.html#token"
      (testing "in Condition"
        (testing "supporting `category` which is a codeable concept"
          (let [resource      "Condition"
                paramater     "category"
                category      "urgent"
                search-params {paramater category}]
            ;; load data into db and stub router
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
              (is (= 200 status)))))))))
