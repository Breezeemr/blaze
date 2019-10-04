(ns blaze.search-params-integration-test
  (:require
   [cheshire.core :as json]
   [clojure.spec.test.alpha :as st]
   [blaze.handler.fhir.search :refer [handler]]
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

(defn db-with [{:strs [entries]}]
  (let [entries        @(bundle/annotate-codes term-service db entries)
        {db :db-after} (d/with db (bundle/code-tx-data db entries))]
    (d/with db (bundle/tx-data db entries))))

(defn- evaluate [db query]
  @(evaluator/evaluate db (OffsetDateTime/now)
                       (compiler/compile-library db (cql/translate query) {})))

(defn read-data [query-name]
  (-> (slurp (str "integration-test/" query-name "/data.json"))
      (json/parse-string)))

(def patient-with-condition (read-data "query-3"))

(def r (atom nil))

(deftest sandbox
  (let [db                             (db-with patient-with-condition)
        {:keys [status body] :as resp} @((handler conn)
                                         {:path-params {:type "Patient"}
                                          :params      {"_summary" "count"}})
        ]
    ;; Retrieves condition given patient id "0"
    #_(d/q '[:find ?condition_id
             :where
             [?c :Condition/id ?condition_id]
             [?c :Condition/subject ?p ]
             [?p :Patient/id "0"]] after-db)

    ;; NOTE the status is 200, but the total is 0.
    ;; I suspect this is because the db is created using datomic.api/with which i believe
    ;; doesn't update the db. So the connection, which were passing to the handler, doesn't
    ;; have the same state.
    (reset! r resp)
    ))

@r
