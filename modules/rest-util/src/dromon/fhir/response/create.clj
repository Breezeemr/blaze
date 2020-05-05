(ns dromon.fhir.response.create
  (:require [clojure.spec.alpha :as s]
            [datomic-spec.core :as ds]
            [datomic.api :as d]
            [dromon.datomic.pull :as pull]
            [dromon.datomic.util :as datomic-util]
            [dromon.handler.fhir.util :as fhir-util]
            [reitit.core :as reitit]
            [ring.util.response :as ring]
            [ring.util.time :as ring-time]))

(s/fdef build-created-response
  :args (s/cat :router reitit/router? :return-preference (s/nilable string?)
               :db ::ds/db :type string? :id string?))

(defn build-created-response
  "Builds a 201 Created response of resource with `type` and `id` from `db`.

  The `router` is used to generate the absolute URL of the Location header and
  `return-preference` is used to decide which type of body is returned."
  [router return-preference db type id]
  (let [last-modified (:db/txInstant (datomic-util/basis-transaction db))
        vid (str (d/basis-t db))]
    (-> (ring/created
          (fhir-util/versioned-instance-url router type id vid)
          (cond
            (= "minimal" return-preference)
            nil
            (= "OperationOutcome" return-preference)
            {:resourceType "OperationOutcome"}
            :else
            (pull/pull-resource db type id)))
        (ring/header "Last-Modified" (ring-time/format-date last-modified))
        (ring/header "ETag" (str "W/\"" vid "\"")))))
