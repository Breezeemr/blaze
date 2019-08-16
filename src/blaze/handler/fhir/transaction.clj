(ns blaze.handler.fhir.transaction
  "FHIR batch/transaction interaction.

  https://www.hl7.org/fhir/http.html#transaction"
  (:require
    [blaze.bundle :as bundle]
    [blaze.datomic.pull :as pull]
    [blaze.datomic.transaction :as tx]
    [blaze.datomic.util :as util]
    [blaze.executors :as ex]
    [blaze.handler.fhir.util :as fhir-util]
    [blaze.handler.util :as handler-util]
    [blaze.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
    [blaze.terminology-service :refer [term-service?]]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [manifold.deferred :as md]
    [reitit.core :as reitit]
    [reitit.ring :as reitit-ring]
    [ring.util.response :as ring]
    [clojure.string :as str])
  (:import
    [java.time.format DateTimeFormatter]))


(defn- validate-entry
  {:arglists '([db entry])}
  [db {:strs [resource] {:strs [method url]} "request" :as entry}]
  (let [[type id] (bundle/match-url url)]
    (cond
      (not (#{"GET" "HEAD" "POST" "PUT" "DELETE" "PATCH"} method))
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown method `" method "`.")
       :fhir/issue "value"}

      (not (#{"GET" "POST" "PUT" "DELETE"} method))
      {::anom/category ::anom/unsupported
       ::anom/message (str "Unsupported method `" method "`.")
       :fhir/issue "not-supported"}

      (nil? type)
      {::anom/category ::anom/incorrect
       ::anom/message "Can't parse type from `entry.request.url` `" url "`."
       :fhir/issue "value"}

      (nil? (util/cached-entity db (keyword type)))
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown type `" type "`.")
       :fhir/issue "value"}

      (and (#{"POST" "PUT"} method) (not (map? resource)))
      {::anom/category ::anom/incorrect
       :fhir/issue "structure"
       :fhir/operation-outcome "MSG_JSON_OBJECT"}

      (and (#{"POST" "PUT"} method) (not= type (get resource "resourceType")))
      {::anom/category ::anom/incorrect
       :fhir/issue "invariant"
       :fhir/operation-outcome "MSG_RESOURCE_TYPE_MISMATCH"}

      (and (= "PUT" method) (nil? id))
      {::anom/category ::anom/incorrect
       ::anom/message "Can't parse id from `entry.request.url` `" url "`."
       :fhir/issue "value"}

      (and (= "PUT" method) (not= id (get resource "id")))
      {::anom/category ::anom/incorrect
       :fhir/issue "invariant"
       :fhir/operation-outcome "MSG_RESOURCE_ID_MISMATCH"}

      :else
      (assoc entry :blaze/type type :blaze/id id))))


(defn- validate-entries
  [db entries]
  (reduce
    (fn [res entry]
      (let [entry (validate-entry db entry)]
        (if (::anom/category entry)
          (reduced entry)
          (conj res entry))))
    []
    entries))


(defn- prepare-entry
  [db {{:strs [method]} "request" :strs [resource] :as entry}]
  (cond
    (= "PUT" method)
    (let [{type "resourceType" id "id"} resource]
      (if-let [resource (util/resource db type id)]
        (assoc entry :blaze/old-resource resource)
        entry))

    (= "POST" method)
    (assoc-in entry ["resource" "id"] (str (d/squuid)))

    :else
    entry))


(defn- validate-and-prepare-bundle
  [db {:strs [resourceType type] entries "entry" :as bundle}]
  (cond
    (not= "Bundle" resourceType)
    (md/error-deferred
      {::anom/category ::anom/incorrect
       ::anom/message (str "Expected a Bundle but was `" resourceType "`.")
       :fhir/issue "value"})

    (not (#{"batch" "transaction"} type))
    (md/error-deferred
      {::anom/category ::anom/incorrect
       ::anom/message
       (str "Expected a Bundle type of batch or transaction but was `" type "`.")
       :fhir/issue "value"})

    :else
    (if (= "transaction" type)
      (let [entries (validate-entries db entries)]
        (if (::anom/category entries)
          (md/error-deferred entries)
          (mapv #(prepare-entry db %) entries)))
      entries)))


(defmulti build-response-entry
  "Builds the response entry."
  {:arglists '([context request-entry tx-result])}
  (fn [_ {{:strs [method]} "request"} _] method))


(defmethod build-response-entry "POST"
  [{:keys [router return-preference]}
   {{type "resourceType" id "id"} "resource"}
   {db :db-after}]
  (let [last-modified (util/tx-instant (util/basis-transaction db))
        vid (str (d/basis-t db))]
    (cond->
      {:response
       {:status "201"
        :etag (str "W/\"" vid "\"")
        :lastModified (str last-modified)
        :location
        (fhir-util/versioned-instance-url router type id vid)}}

      (= "representation" return-preference)
      (assoc :resource (pull/pull-resource db type id)))))


(defmethod build-response-entry "PUT"
  [{:keys [router return-preference]}
   {{type "resourceType" id "id"} "resource" :blaze/keys [old-resource]}
   {db :db-after}]
  (let [last-modified (util/tx-instant (util/basis-transaction db))
        vid (str (d/basis-t db))]
    (cond->
      {:response
       (cond->
         {:status (if old-resource "200" "201")
          :etag (str "W/\"" vid "\"")
          :lastModified (str last-modified)}
         (nil? old-resource)
         (assoc
           :location
           (fhir-util/versioned-instance-url router type id vid)))}

      (= "representation" return-preference)
      (assoc :resource (pull/pull-resource db type id)))))


(defmethod build-response-entry "DELETE"
  [_ _ {db :db-after}]
  (let [last-modified (util/tx-instant (util/basis-transaction db))
        vid (d/basis-t db)]
    {:response
     {:status "204"
      :etag (str "W/\"" vid "\"")
      :lastModified (str last-modified)}}))


(defn- strip-leading-slash [s]
  (if (str/starts-with? s "/")
    (subs s 1)
    s))


(defn- convert-http-date
  "Converts string `s` representing a HTTP date into a FHIR instant formatted
  string."
  [s]
  (->> (.parse DateTimeFormatter/RFC_1123_DATE_TIME s)
       (.format DateTimeFormatter/ISO_INSTANT)))


(defn- process-batch-entry
  [{:keys [handler]} {{:strs [method url]} "request" :strs [resource]}]
  (let [url (strip-leading-slash (str/trim url))
        [url query-string] (str/split url #"\?")]
    (if (= "" url)
      (handler-util/bundle-error-response
        {::anom/category ::anom/incorrect
         ::anom/message (format "Invalid URL `%s` in bundle request." url)
         :fhir/issue "value"})
      (let [request
            (cond->
              {:uri url :request-method (keyword (str/lower-case method))}

              query-string
              (assoc :query-string query-string)

              resource
              (assoc :body resource))]
        (-> (handler request)
            (md/chain'
              (fn [{:keys [status body]
                    {etag "ETag"
                     last-modified "Last-Modified"
                     location "Location"}
                    :headers}]
                (cond->
                  {:response
                   (cond->
                     {:status (str status)}

                     etag
                     (assoc :etag etag)

                     last-modified
                     (assoc :lastModified (convert-http-date last-modified))

                     location
                     (assoc :location location))}

                  (and (#{200 201} status) body)
                  (assoc :resource body)

                  (<= 400 status)
                  (update :response assoc :outcome body)))))))))


(defmulti process
  "Processes the prepared entries according the batch or transaction rules and
  returns the response entries."
  {:arglists '([context type request-entries])}
  (fn [_ type _] type))


(defmethod process "batch"
  [context _ request-entries]
  (md/loop [[request-entry & request-entries] request-entries
            response-entries []]
    (if request-entry
      (if (:response request-entry)
        (md/recur request-entries (conj response-entries request-entry))
        (-> (process-batch-entry context request-entry)
            (md/chain'
              (fn [response-entry]
                (md/recur request-entries (conj response-entries response-entry))))
            (md/catch'
              (fn [error]
                (let [response (handler-util/bundle-error-response error)]
                  (md/recur request-entries (conj response-entries {:response response})))))))
      response-entries)))


(defn- transact-resources
  [{:keys [conn db] :as context} request-entries]
  (-> (tx/transact-async conn (bundle/tx-data db request-entries))
      (md/chain'
        (fn [tx-result]
          (mapv
            #(build-response-entry context % tx-result)
            request-entries)))))


(defmethod process "transaction"
  [{:keys [conn term-service db] :as context} _ request-entries]
  (-> (bundle/annotate-codes term-service db request-entries)
      (md/chain'
        (fn [request-entries]
          (let [code-tx-data (bundle/code-tx-data db request-entries)]
            (if (empty? code-tx-data)
              (transact-resources context request-entries)
              (-> (tx/transact-async conn code-tx-data)
                  (md/chain'
                    (fn [{db :db-after}]
                      (transact-resources (assoc context :db db) request-entries))))))))))


(defn- handler-intern [conn term-service executor]
  (fn [{{:strs [type] :as bundle} :body :keys [headers] ::reitit/keys [router]}]
    (let [db (d/db conn)]
      (-> (md/future-with executor
            (validate-and-prepare-bundle db bundle))
          (md/chain'
            (let [context
                  {:router router
                   :handler (reitit-ring/ring-handler router)
                   :conn conn
                   :term-service term-service
                   :db db
                   :return-preference (handler-util/preference headers "return")}]
              #(process context type %)))
          (md/chain'
            (fn [response-entries]
              (ring/response
                {:resourceType "Bundle"
                 :type (str type "-response")
                 :entry response-entries})))
          (md/catch' handler-util/error-response)))))


(defn wrap-interaction-name [handler]
  (fn [{{:strs [type]} :body :as request}]
    (-> (handler request)
        (md/chain'
          (fn [response]
            (assoc response :fhir/interaction-name type))))))


(s/def :handler.fhir/transaction fn?)


(s/fdef handler
  :args (s/cat :conn ::ds/conn :term-service term-service? :executor ex/executor?)
  :ret :handler.fhir/transaction)

(defn handler
  ""
  [conn term-service executor]
  (-> (handler-intern conn term-service executor)
      (wrap-interaction-name)
      (wrap-observe-request-duration)))
