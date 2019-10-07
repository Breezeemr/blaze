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

(def patient-with-condition (read-data "query-3"))

;; TODO 1. fails with subject 1 as null pointer executable.
;; TODO 2. Make a test to lookup something other then test id, like by code. Code's value will be more complex will have to parse it.
;; TODO 3. use other query data.
;; TODO get references and coding.


(deftest sandbox
  (testing "Given a Patient with Condition, when a server gets a FHIR search query"
    (testing "with just just one filter"
      ;; load data into db and stub router
      (let [resource "Condition"
            id       "0"]
        (db-with patient-with-condition)
        (fhir-test-util/stub-instance-url ::router resource id ::full-url)
        (let [{:keys [status body]} @((handler conn)
                                      {:path-params    {:type resource}
                                       ::reitit/router ::router
                                       :params         {"subject" id}})
              returned-resource-ids (into #{}
                                          (->> body
                                               :entry
                                               (map #(get-in % [:resource "id"]))))]

          (is (= 200 status))
          (is (contains? returned-resource-ids id)))))))

;; the below resource from the response is the same as we put into the db. so this query is working. Will have to consider if the router being stubbed in this way is acceptable.
;; => {"code" {"coding" [{"version" "2019", "system" "http://fhir.de/CodeSystem/dimdi/icd-10-gm", "code" "M06.9"}]}, "subject" {"reference" "Patient/0"}, "id" "0", "onsetDateTime" "2018", "resourceType" "Condition", "meta" {"versionId" "9841", "lastUpdated" "2019-10-07T14:05:59.085Z"}}

;; Example datomic query that retrieves condition given patient id "0"
#_(d/q '[:find ?condition_id
         :where
         [?c :Condition/id ?condition_id]
         [?c :Condition/subject ?p]
         [?p :Patient/id "0"]] db)

;; Example of how to query given a Conditions based on is code.
#_(d/q '[:find ?id
         :where
         [?code :code/code "M06.9"]
         [?condition :Condition.index/code ?code]
         [?condition :Condition/id ?id]
         ]
       (:db @y))
;; => #{["0"]}




