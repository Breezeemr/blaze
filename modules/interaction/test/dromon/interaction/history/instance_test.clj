(ns dromon.interaction.history.instance-test
  "Specifications relevant for the FHIR history interaction:

  https://www.hl7.org/fhir/http.html#history
  https://www.hl7.org/fhir/operationoutcome.html
  https://www.hl7.org/fhir/http.html#ops"
  (:require
    [dromon.datomic.test-util :as datomic-test-util]
    [dromon.interaction.history.test-util :as history-test-util]
    [dromon.interaction.history.instance :refer [handler]]
    [dromon.interaction.test-util :as test-util]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest is testing]]
    [datomic-spec.test :as dst]
    [reitit.core :as reitit]
    [taoensso.timbre :as log]))


(defn fixture [f]
  (st/instrument)
  (dst/instrument)
  (st/instrument
    [`handler]
    {:spec
     {`handler
      (s/fspec
        :args (s/cat :conn #{::conn}))}})
  (log/with-merged-config {:level :error} (f))
  (st/unstrument))


(test/use-fixtures :each fixture)


(deftest handler-test
  (testing "Returns Not Found on Non-Existing Resource"
    (test-util/stub-t ::query-params nil?)
    (test-util/stub-db ::conn nil? ::db)
    (datomic-test-util/stub-resource ::db #{"Patient"} #{"0"} nil?)

    (let [{:keys [status body]}
          @((handler ::conn)
            {:query-params ::query-params
             :path-params {:id "0"}
             ::reitit/match {:data {:fhir.resource/type "Patient"}}})]

      (is (= 404 status))

      (is (= "OperationOutcome" (:resourceType body)))

      (is (= "error" (-> body :issue first :severity)))

      (is (= "not-found" (-> body :issue first :code)))))

  (testing "Returns History with one Patient"
    (let [patient {:instance/version ::foo}
          match {:data {:fhir.resource/type "Patient"}}]
      (test-util/stub-t ::query-params nil?)
      (test-util/stub-db ::conn nil? ::db)
      (datomic-test-util/stub-resource ::db #{"Patient"} #{"0"} #{{:db/id 0}})
      (history-test-util/stub-page-t ::query-params nil?)
      (history-test-util/stub-since-t ::db ::query-params nil?)
      (history-test-util/stub-tx-db ::db nil? nil? ::db)
      (datomic-test-util/stub-instance-transaction-history ::db 0 [::tx])
      (test-util/stub-page-size ::query-params 50)
      (datomic-test-util/stub-as-of-t ::db nil?)
      (datomic-test-util/stub-basis-t ::db 173105)
      (datomic-test-util/stub-entity ::db #{0} #{patient})
      (datomic-test-util/stub-ordinal-version patient 1)
      (history-test-util/stub-nav-link
        match ::query-params 173105 ::tx nil?
        (constantly ::self-link-url))
      (history-test-util/stub-build-entry
        ::router ::db #{::tx} #{0} (constantly ::entry))

      (let [{:keys [status body]}
            @((handler ::conn)
              {::reitit/router ::router
               ::reitit/match match
               :query-params ::query-params
               :path-params {:id "0"}})]

        (is (= 200 status))

        (is (= "Bundle" (:resourceType body)))

        (is (= "history" (:type body)))

        (is (= 1 (:total body)))

        (is (= 1 (count (:entry body))))

        (is (= "self" (-> body :link first :relation)))

        (is (= ::self-link-url (-> body :link first :url)))

        (is (= ::entry (-> body :entry first)))))))
