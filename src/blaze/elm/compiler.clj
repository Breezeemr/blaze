(ns blaze.elm.compiler
  "Compiles ELM expressions to expressions defined by the `Expression`
  protocol in this namespace.

  Section numbers are according to
  https://cql.hl7.org/04-logicalspecification.html.

  Regarding time zones:
    We use date and time values with and without time zone information here.
    Every local (without time zone) date or time is meant relative to the time
    zone of the :now timestamp in the evaluation context."
  (:require
    [camel-snake-kebab.core :refer [->kebab-case-string]]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [cognitect.anomalies :as anom]
    [datomic-spec.core :as ds]
    [blaze.elm.compiler.protocols :refer [Expression -eval -hash]]
    [blaze.elm.compiler.retrieve :as retrieve]
    [blaze.datomic.cql :as cql]
    [blaze.datomic.util :as datomic-util]
    [blaze.datomic.value :as dv]
    [blaze.elm.aggregates :as aggregates]
    [blaze.elm.boolean]
    [blaze.elm.data-provider :as data-provider]
    [blaze.elm.date-time :as date-time :refer [local-time]]
    [blaze.elm.decimal :as decimal]
    [blaze.elm.deps-infer :refer [infer-library-deps]]
    [blaze.elm.equiv-relationships :refer [find-equiv-rels-library]]
    [blaze.elm.integer]
    [blaze.elm.interval :refer [interval interval?]]
    [blaze.elm.list]
    [blaze.elm.nil]
    [blaze.elm.normalizer :refer [normalize-library]]
    [blaze.elm.protocols :as p]
    [blaze.elm.quantity :refer [quantity]]
    [blaze.elm.spec]
    [blaze.elm.string :as string]
    [blaze.elm.type-infer :refer [infer-library-types]]
    [blaze.elm.util :as elm-util]
    [blaze.util :as u]
    [datomic.api :as d])
  (:import
    [clojure.core Eduction]
    [java.time LocalDate LocalDateTime OffsetDateTime Year YearMonth ZoneOffset]
    [java.time.temporal ChronoUnit Temporal]
    [javax.measure Quantity]
    [java.util Comparator])
  (:refer-clojure :exclude [comparator compile]))


(extend-protocol Expression
  nil
  (-eval [this _ _ _]
    this)
  (-hash [this]
    this)

  Object
  (-eval [this _ _ _]
    this)
  (-hash [this]
    this))


(extend-protocol p/Equal
  Object
  (equal [x y]
    (some->> y (= x))))


(extend-protocol p/Equivalent
  Object
  (equivalent [x y]
    (= x y)))


(extend-protocol p/Greater
  Comparable
  (greater [x y]
    (some->> y (.compareTo x) (< 0))))


(extend-protocol p/GreaterOrEqual
  Comparable
  (greater-or-equal [x y]
    (some->> y (.compareTo x) (<= 0))))


(extend-protocol p/Less
  Comparable
  (less [x y]
    (some->> y (.compareTo x) (> 0))))


(extend-protocol p/LessOrEqual
  Comparable
  (less-or-equal [x y]
    (some->> y (.compareTo x) (>= 0))))


(defmulti compile*
  "Compiles `expression`."
  {:arglists '([context expression])}
  (fn [_ {:keys [type]}]
    (keyword "elm.compiler.type" (->kebab-case-string type))))


(defmethod compile* :default
  [_ {:keys [type]}]
  (throw (Exception. (str "Unsupported ELM expression type: " (or type "<missing>")))))


(s/def :life/expression
  #(satisfies? Expression %))


(s/def ::now
  #(satisfies? OffsetDateTime %))


(s/def ::library-context
  (s/map-of string? some?))


(s/def ::eval-context
  (s/keys :req-un [::ds/db ::now] :opt-un [::library-context]))


(s/def ::compile-context
  (s/keys :req-un [:elm/library]))


(s/fdef compile
  :args (s/cat :context ::compile-context :expression :elm/expression)
  :ret :life/expression)

(defn compile [context expression]
  (compile* context expression))


(s/def :life/compiled-expression-def
  (s/merge :elm/expression-def (s/keys :req [:life/expression])))


(defn- compile-expression-def
  "Compiles the expression of `expression-def` in `context` and assocs the
  resulting compiled expression under :life/expression to the `expression-def`
  which itself is returned.

  In case compilation leads to an error, an anomaly is returned."
  {:arglists '([context expression-def])}
  [context {:keys [expression] :as expression-def}]
  (let [context (assoc context :eval-context (:context expression-def))]
    (try
      (assoc expression-def :life/expression (compile context expression))
      (catch Exception e
        #::anom
            {:category ::anom/fault
             :message (ex-message e)
             :_/e e
             :elm/expression expression}))))


(s/def :life/compiled-expression-defs
  (s/coll-of :life/compiled-expression-def))


(s/def :life/compiled-library
  (s/keys :req [:life/compiled-expression-defs]))


(s/fdef compile-library
  :args (s/cat :db ::ds/db :library :elm/library :opts map?)
  :ret (s/or :result :life/compiled-library
             :anomaly ::anom/anomaly))

(defn compile-library
  "Compiles `library` using `db` to retrieve codes.

  There are currently no options."
  [db library opts]
  (let [library (-> library
                    normalize-library
                    find-equiv-rels-library
                    infer-library-deps
                    infer-library-types)
        context (assoc opts :db db :library library)
        expr-defs (transduce
                    (map #(compile-expression-def context %))
                    (completing
                      (fn [r compiled-expression-def]
                        (if (::anom/category compiled-expression-def)
                          (reduced compiled-expression-def)
                          (conj r compiled-expression-def))))
                    []
                    (-> library :statements :def))]
    (if (::anom/category expr-defs)
      expr-defs
      {:life/compiled-expression-defs expr-defs})))


(defn static? [x]
  (not (instance? blaze.elm.compiler.protocols.Expression x)))


(defmacro defunop
  {:arglists '([name attr-map? bindings & body])}
  [name & more]
  (let [attr-map (when (map? (first more)) (first more))
        more (if (map? (first more)) (next more) more)
        [[operand-binding expr-binding] & body] more]
    `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
       [context# expr#]
       (let [operand# (compile (merge context# ~attr-map) (:operand expr#))
             ~(or expr-binding '_) expr#]
         (if (static? operand#)
           (let [~operand-binding operand#]
             ~@body)
           (reify Expression
             (-eval [~'_ context# resource# scope#]
               (let [~operand-binding (-eval operand# context# resource# scope#)]
                 ~@body))
             (-hash [_]
               {:type ~(keyword (clojure.core/name name))
                :operand (-hash operand#)})))))))


(defmacro defbinop
  {:arglists '([name bindings & body])}
  [name [op-1-binding op-2-binding] & body]
  `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
     [context# {[operand-1# operand-2#] :operand}]
     (let [operand-1# (compile context# operand-1#)
           operand-2# (compile context# operand-2#)]
       (reify Expression
         (-eval [~'_ context# resource# scope#]
           (let [~op-1-binding (-eval operand-1# context# resource# scope#)
                 ~op-2-binding (-eval operand-2# context# resource# scope#)]
             ~@body))
         (-hash [_]
           {:type ~(keyword (clojure.core/name name))
            :operands [(-hash operand-1#) (-hash operand-2#)]})))))


(defmacro defternop
  {:arglists '([name bindings & body])}
  [name [op-1-binding op-2-binding op-3-binding] & body]
  `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
     [context# {[operand-1# operand-2# operand-3#] :operand}]
     (let [operand-1# (compile context# operand-1#)
           operand-2# (compile context# operand-2#)
           operand-3# (compile context# operand-3#)]
       (reify Expression
         (-eval [~'_ context# resource# scope#]
           (let [~op-1-binding (-eval operand-1# context# resource# scope#)
                 ~op-2-binding (-eval operand-2# context# resource# scope#)
                 ~op-3-binding (-eval operand-3# context# resource# scope#)]
             ~@body))
         (-hash [_]
           {:type ~(keyword (clojure.core/name name))
            :operands [(-hash operand-1#) (-hash operand-2#) (-hash operand-3#)]})))))


(defmacro defnaryop
  {:arglists '([name bindings & body])}
  [name [operands-binding] & body]
  `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
     [context# {operands# :operand}]
     (let [operands# (mapv #(compile context# %) operands#)]
       (reify Expression
         (-eval [~'_ context# resource# scope#]
           (let [~operands-binding (mapv #(-eval % context# resource# scope#) operands#)]
             ~@body))
         (-hash [_]
           {:type ~(keyword (clojure.core/name name))
            :operands (mapv -hash operands#)})))))


(defmacro defaggop
  {:arglists '([name bindings & body])}
  [name [source-binding] & body]
  `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
     [{data-provider# :data-provider :as context#} {source# :source path# :path}]
     (let [source# (compile context# source#)
           path# (some-> path# (data-provider/compile-path data-provider#))]
       (if path#
         (reify Expression
           (-eval [~'_ context# resource# scope#]
             (let [source# (-eval source# context# resource# scope#)
                   ~source-binding (data-provider/resolve-property
                                     data-provider# source# path#)]
               ~@body))
           (-hash [_]
             {:type ~(keyword (clojure.core/name name))
              :source (-hash source#)
              :path path#}))
         (reify Expression
           (-eval [~'_ context# resource# scope#]
             (let [~source-binding (-eval source# context# resource# scope#)]
               ~@body))
           (-hash [_]
             {:type ~(keyword (clojure.core/name name))
              :source (-hash source#)}))))))


(defn- to-chrono-unit [precision]
  (case (str/lower-case precision)
    "year" ChronoUnit/YEARS
    "month" ChronoUnit/MONTHS
    "week" ChronoUnit/WEEKS
    "day" ChronoUnit/DAYS
    "hour" ChronoUnit/HOURS
    "minute" ChronoUnit/MINUTES
    "second" ChronoUnit/SECONDS
    "millisecond" ChronoUnit/MILLIS))


(defmacro defbinopp
  {:arglists '([name bindings & body])}
  [name [op-1-binding op-2-binding precision-binding] & body]
  `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
     [context# {[operand-1# operand-2#] :operand precision# :precision}]
     (let [operand-1# (compile context# operand-1#)
           operand-2# (compile context# operand-2#)
           ~precision-binding (some-> precision# to-chrono-unit)]
       (reify Expression
         (-eval [~'_ context# resource# scope#]
           (let [~op-1-binding (-eval operand-1# context# resource# scope#)
                 ~op-2-binding (-eval operand-2# context# resource# scope#)]
             ~@body))
         (-hash [_]
           {:type ~(keyword (clojure.core/name name))
            :operands [(-hash operand-1#) (-hash operand-2#)]})))))


(defmacro defunopp
  {:arglists '([name bindings & body])}
  [name [operand-binding precision-binding expr-binding] & body]
  `(defmethod compile* ~(keyword "elm.compiler.type" (clojure.core/name name))
     [context# {operand# :operand precision# :precision :as expr#}]
     (let [operand# (compile context# operand#)
           ~precision-binding (some-> precision# to-chrono-unit)
           ~(or expr-binding '_) expr#]
       (reify Expression
         (-eval [~'_ context# resource# scope#]
           (let [~operand-binding (-eval operand# context# resource# scope#)]
             ~@body))
         (-hash [_]
           {:type ~(keyword (clojure.core/name name))
            :operands (-hash operand#)})))))


(defn- literal? [x]
  (or (boolean? x)
      (number? x)
      (string? x)
      (instance? Temporal x)
      (instance? Quantity x)))


(defn- append-locator [msg locator]
  (if locator
    (str msg " " locator ".")
    (str msg ".")))



;; 1. Simple Values

;; 1.1 Literal
;;
;; The Literal type defines a single scalar value. For example, the literal 5,
;; the boolean value true or the string "antithrombotic".
(defmethod compile* :elm.compiler.type/literal
  [_ {:keys [value] value-type :valueType}]
  (when value
    (let [[value-type-ns value-type-name] (elm-util/parse-qualified-name value-type)]
      (case value-type-ns
        "urn:hl7-org:elm-types:r1"
        (case value-type-name
          "Boolean" (Boolean/valueOf ^String value)
          ;; TODO: maybe we can even use integers here
          "Integer" (long (Integer/parseInt value))
          "Decimal" (decimal/from-literal value)
          "String" value
          (throw (Exception. (str value-type-name " literals are not supported"))))))))


;; 2. Structured Values

;; 2.1. Tuple
(defmethod compile* :elm.compiler.type/tuple
  [context {elements :element}]
  (let [elements
        (reduce
          (fn [r {:keys [name value]}]
            (assoc r name (compile context value)))
          {}
          elements)]
    (reify Expression
      (-eval [_ context resource scope]
        (reduce-kv
          (fn [r name value]
            (assoc r (keyword name) (-eval value context resource scope)))
          {}
          elements))
      (-hash [_]
        {:type :tuple
         :elements
         (reduce-kv
           (fn [r name value]
             (assoc r name (-hash value)))
           {}
           elements)}))))


(defn- source-property-expression
  "Creates an Expression which returns the property of `attr-kw` from `source`."
  [attr-kw source]
  (reify Expression
    (-eval [_ context resource scope]
      (let [res (-eval source context resource scope)]
        (attr-kw res)))
    (-hash [_]
      {:type :property
       :attr-kw attr-kw
       :source (-hash source)})))


(defn- time-source-property-expression
  "Creates an Expression which returns the property of `attr-kw` of type date,
  dateTime or time from `source`. Such properties have to be deserialized from
  byte array."
  [attr-kw source]
  (reify Expression
    (-eval [_ context resource scope]
      (dv/read (attr-kw (-eval source context resource scope))))
    (-hash [_]
      {:type :property
       :attr-kw attr-kw
       :source (-hash source)})))


(defn- quantity-source-property-expression
  "Creates an Expression which returns the property of `attr-kw` of type
  Quantity from `source`. Such properties have to be deserialized from byte
  array."
  [attr-kw source]
  (reify Expression
    (-eval [_ context resource scope]
      (dv/read (attr-kw (-eval source context resource scope))))
    (-hash [_]
      {:type :property
       :attr-kw attr-kw
       :source (-hash source)})))


(defn- scope-property-expression
  "Creates an Expression which returns the property of `attr-kw` from either
  a direct supplied entity or an entity from query context by `scope`."
  ([attr-kw]
   (reify Expression
     (-eval [_ _ _ entity]
       (attr-kw entity))
     (-hash [_]
       {:type :property
        :attr-kw attr-kw})))
  ([attr-kw scope]
   (reify Expression
     (-eval [_ _ _ query-context]
       (attr-kw (get query-context scope)))
     (-hash [_]
       {:type :property
        :attr-kw attr-kw
        :scope scope}))))


(defn- time-scope-property-expression
  "Creates an Expression which returns a property of `attr-kw` of type date,
  dateTime or time from either a direct supplied entity or an entity from query
  context by `scope`. Such properties have to be deserialized from byte array."
  ([attr-kw]
   (reify Expression
     (-eval [_ _ _ entity]
       (dv/read (attr-kw entity)))
     (-hash [_]
       {:type :property
        :attr-kw attr-kw})))
  ([attr-kw scope]
   (reify Expression
     (-eval [_ _ _ query-context]
       (dv/read (attr-kw (get query-context scope))))
     (-hash [_]
       {:type :property
        :attr-kw attr-kw
        :scope scope}))))


(defn- quantity-scope-property-expression
  "Creates an Expression which returns a property of `attr-kw` of type Quantity
  from either a direct supplied entity or an entity from query context by
  `scope`. Such properties have to be deserialized from byte array."
  ([attr-kw]
   (reify Expression
     (-eval [_ _ _ entity]
       (dv/read (attr-kw entity)))
     (-hash [_]
       {:type :property
        :attr-kw attr-kw})))
  ([attr-kw scope]
   (reify Expression
     (-eval [_ _ _ query-context]
       (dv/read (attr-kw (get query-context scope))))
     (-hash [_]
       {:type :property
        :attr-kw attr-kw
        :scope scope}))))


(defn- tuple-type-specifier?
  {:arglists '([type-specifier])}
  [{:keys [type]}]
  (= "TupleTypeSpecifier" type))


(defn- choice-type-specifier?
  {:arglists '([type-specifier])}
  [{:keys [type]}]
  (= "ChoiceTypeSpecifier" type))


(defn- list-type-specifier?
  {:arglists '([type-specifier])}
  [{:keys [type]}]
  (= "ListTypeSpecifier" type))


(defn contains-choice-type?
  {:arglists '([choice-type-specifier type])}
  [{choices :choice} type]
  (some #(and (= (:type type) (:type %))
              (= (:name type) (:name %))) choices))


(defn- extract-local-fhir-name [type-name]
  (let [[ns name] (elm-util/parse-qualified-name type-name)]
    (if (= "http://hl7.org/fhir" ns)
      name
      (throw (Exception. (str "Unsupported type namespace `"
                              ns "` in `Property` expression."))))))


(defn- first-lower-case [s]
  (let [[^char ch & chs] s]
    (if (Character/isUpperCase ch)
      (apply str (cons (Character/toLowerCase ch) chs))
      s)))


(defn- attr-kw* [source-type-name path]
  (let [[first-type-name & type-names] (str/split source-type-name #"\.")]
    (keyword
      (str/join "." (cons first-type-name (map first-lower-case type-names)))
      path)))


(defn attr-kw
  {:arglists '([property-expression])}
  [{:life/keys [source-type as-type] :keys [path source]
    result-type-specifier :resultTypeSpecifier :as expr}]
  (cond
    source-type
    (let [[source-type-ns source-type-name] (elm-util/parse-qualified-name source-type)]
      (if (= "http://hl7.org/fhir" source-type-ns)
        (if (choice-type-specifier? result-type-specifier)
          (if (contains-choice-type? result-type-specifier as-type)
            (attr-kw* source-type-name (str path (u/title-case (extract-local-fhir-name (:name as-type)))))
            (throw (ex-info (str "Ambiguous choice type on property `"
                                 source-type-name "/" path "`.") expr)))
          (attr-kw* source-type-name path))
        (throw (ex-info (str "Unsupported source type namespace `"
                             source-type-ns "` in property expression.") expr))))
    source
    (let [{type-specifier :resultTypeSpecifier type-name :resultTypeName} source]
      (cond
        (tuple-type-specifier? type-specifier)
        (keyword path)

        type-name
        (attr-kw (assoc expr :life/source-type type-name))

        ;; TODO: HACK
        (= "birthDate.value" path)
        :Patient/birthDate

        :else
        (throw (ex-info "Unable to determine attr-kw on property expression." expr))))
    :else
    (throw (ex-info "Unable to determine attr-kw on property expression." expr))))


(defn- property-result-type
  {:arglists '([property-expression])}
  [{result-type-name :resultTypeName
    result-type-specifier :resultTypeSpecifier
    :keys [path]
    :life/keys [as-type]
    :as expr}]
  (cond
    result-type-name
    (elm-util/parse-qualified-name result-type-name)

    (choice-type-specifier? result-type-specifier)
    (if (contains-choice-type? result-type-specifier as-type)
      (elm-util/parse-qualified-name (:name as-type))
      (throw (ex-info "Ambiguous choice type on property." expr)))

    (list-type-specifier? result-type-specifier)
    (elm-util/parse-qualified-name (-> result-type-specifier :elementType :name))

    ;; TODO: HACK
    (= "birthDate.value" path)
    ["http://hl7.org/fhir" "date"]

    :else
    (throw (ex-info "Undetermined result type on property." expr))))


;; 2.3. Property
;;
;; The Property operator returns the value of the property on source specified
;; by the path attribute.
;;
;; If the result of evaluating source is null, the result is null.
;;
;; The path attribute may include qualifiers (.) and indexers ([x]). Indexers
;; must be literal integer values.
;;
;; If the path attribute contains qualifiers or indexers, each qualifier or
;; indexer is traversed to obtain the actual value. If the object of the
;; property access at any point in traversing the path is null, the result is
;; null.
;;
;; If a scope is specified, the name is used to resolve the scope in which the
;; path will be resolved. Scopes can be named by operators such as Filter and
;; ForEach.
;;
;; Property expressions can also be used to access the individual points and
;; closed indicators for interval types using the property names low, high,
;; lowClosed, and highClosed.
(defmethod compile* :elm.compiler.type/property
  [{:life/keys [single-query-scope] :as context}
   {:keys [source scope] :as expression}]
  (let [[result-type-ns result-type-name] (property-result-type expression)
        attr-kw (attr-kw expression)
        source (some->> source (compile context))]
    (cond
      ;; We evaluate the `source` to retrieve the entity.
      source
      (let [property-expression-fn
            (case result-type-ns
              "http://hl7.org/fhir"
              (case result-type-name
                ("date" "dateTime" "time")
                time-source-property-expression
                "Quantity"
                quantity-source-property-expression
                source-property-expression)
              source-property-expression)]
        (property-expression-fn attr-kw source))

      ;; We use the `scope` to retrieve the entity from `query-context`.
      scope
      (let [property-expression-fn
            (case result-type-ns
              "http://hl7.org/fhir"
              (case result-type-name
                ("date" "dateTime" "time")
                time-scope-property-expression
                "Quantity"
                quantity-scope-property-expression
                scope-property-expression)
              scope-property-expression)]
        (if (= single-query-scope scope)
          (property-expression-fn attr-kw)
          (property-expression-fn attr-kw scope))))))



;; 3. Clinical Values

(defn- find-code-system-def
  "Returns the code-system-def with `name` from `library` or nil if not found."
  {:arglists '([library name])}
  [{{code-system-defs :def} :codeSystems} name]
  (some #(when (= name (:name %)) %) code-system-defs))


;; 3.1. Code
(defmethod compile* :elm.compiler.type/code
  [{:keys [library db] :as context}
   {{system-name :name} :system :keys [code] :as expression}]
  ;; TODO: look into other libraries (:libraryName)
  (if-let [{system :id} (find-code-system-def library system-name)]
    (cql/find-code db system code)
    (throw (ex-info (format "Can't find the code system `%s`." system-name)
                    {:context context
                     :expression expression}))))


;; 3.3. CodeRef
(defn- find-code-def
  "Returns the code-def with `name` from `library` or nil if not found."
  {:arglists '([library name])}
  [{{code-defs :def} :codes} name]
  (some #(when (= name (:name %)) %) code-defs))

(defmethod compile* :elm.compiler.type/code-ref
  [{:keys [library db] :as context} {:keys [name] :as expression}]
  ;; TODO: look into other libraries (:libraryName)
  (when-let [{code-system-ref :codeSystem code :id :as code-def}
             (find-code-def library name)]
    (if code-system-ref
      (when-let [{:keys [version] system :id} (compile context (assoc code-system-ref :type "CodeSystemRef"))]
        ;; TODO: version
        (cql/find-code db system code))
      (throw (ex-info "Can't handle code-defs without code-system-ref."
                      {:context context
                       :expression expression
                       :code-def code-def})))))


;; 3.5. CodeSystemRef
(defmethod compile* :elm.compiler.type/code-system-ref
  [{:keys [library]} {:keys [name]}]
  ;; TODO: look into other libraries (:libraryName)
  (find-code-system-def library name))


;; 3.9. Quantity
(defmethod compile* :elm.compiler.type/quantity
  [_ {:keys [value unit]}]
  (if unit
    (case unit
      ("year" "years") (date-time/period value 0 0)
      ("month" "months") (date-time/period 0 value 0)
      ("week" "weeks") (date-time/period 0 0 (* value 7 24 3600000))
      ("day" "days") (date-time/period 0 0 (* value 24 3600000))
      ("hour" "hours") (date-time/period 0 0 (* value 3600000))
      ("minute" "minutes") (date-time/period 0 0 (* value 60000))
      ("second" "seconds") (date-time/period 0 0 (* value 1000))
      ("millisecond" "milliseconds") (date-time/period 0 0 value)
      (quantity value unit))
    value))



;; 9. Reusing Logic

;; 9.2. ExpressionRef
;;
;; The ExpressionRef type defines an expression that references a previously
;; defined NamedExpression. The result of evaluating an ExpressionReference is
;; the result of evaluating the referenced NamedExpression.
(defmethod compile* :elm.compiler.type/expression-ref
  [{:keys [eval-context]} {:keys [name] def-eval-context :life/eval-context}]
  ;; TODO: look into other libraries (:libraryName)
  (when name
    (cond
      (and (= "Unspecified" eval-context) (not= "Unspecified" def-eval-context))
      ;; The referenced expression has a concrete context but we are in the
      ;; Unspecified context. So we map the referenced expression over all
      ;; concrete resources.
      (reify Expression
        (-eval [_ {:keys [db library-context] :as context} _ _]
          (if-some [expression (get library-context name)]
            (mapv
              #(-eval expression context % nil)
              (datomic-util/list-resources db def-eval-context))
            (throw (ex-info (str "Expression `" name "` not found.")
                            {:context context}))))
        (-hash [_]
          ;; TODO: the expression does something different here. should it have a different hash?
          {:type :expression-ref
           :name name}))

      :else
      (reify Expression
        (-eval [_ {:keys [library-context] :as context} resource _]
          (if-some [expression (get library-context name)]
            (-eval expression context resource nil)
            (throw (ex-info (str "Expression `" name "` not found.")
                            {:context context}))))
        (-hash [_]
          {:type :expression-ref
           :name name})))))


;; 9.4. FunctionRef
(defmethod compile* :elm.compiler.type/function-ref
  [context {:keys [name] operands :operand}]
  ;; TODO: look into other libraries (:libraryName)
  (let [operands (mapv #(compile context %) operands)]
    (case name
      "ToQuantity"
      (let [operand (first operands)]
        (reify Expression
          (-eval [_ context resource scope]
            (-eval operand context resource scope))
          (-hash [_]
            {:type :function-ref
             :name name
             :operand (-hash operand)})))

      "ToDate"
      (let [operand (first operands)]
        (reify Expression
          (-eval [_ {:keys [now] :as context} resource scope]
            (p/to-date (-eval operand context resource scope) now))
          (-hash [_]
            {:type :function-ref
             :name name
             :operand (-hash operand)})))

      "ToDateTime"
      (let [operand (first operands)]
        (reify Expression
          (-eval [_ {:keys [now] :as context} resource scope]
            (p/to-date-time (-eval operand context resource scope) now))
          (-hash [_]
            {:type :function-ref
             :name name
             :operand (-hash operand)})))

      "ToString"
      (let [operand (first operands)]
        (reify Expression
          (-eval [_ context resource scope]
            (let [value (-eval operand context resource scope)]
              (str (or (:code/code value) value))))
          (-hash [_]
            {:type :function-ref
             :name name
             :operand (-hash operand)})))

      "ToCode"
      (let [operand (first operands)]
        (reify Expression
          (-eval [_ context resource scope]
            (:Coding/code (-eval operand context resource scope)))
          (-hash [_]
            {:type :function-ref
             :name name
             :operand (-hash operand)})))

      (throw (Exception. (str "Unsupported function `" name "` in `FunctionRef` expression."))))))



;; 10. Queries
(declare compile-with-equiv-clause)

;; 10.1. Query
;;
;; The Query operator represents a clause-based query. The result of the query
;; is determined by the type of sources included, as well as the clauses used in
;; the query.
(defn- with-xform [with-clause]
  (fn create-with-xform [context resource scope]
    (let [with-clause (with-clause context resource scope)]
      (filter #(with-clause context resource %)))))


(defn- where-xform [alias where-expr]
  (fn [context resource scope]
    (filter #(-eval where-expr context resource (assoc scope alias %)))))


(defn- return-xform [alias return-expr]
  (fn [context resource scope]
    (map #(-eval return-expr context resource (assoc scope alias %)))))


(defn- comp-xforms [context resource scope xforms]
  (transduce (map #(% context resource scope)) comp xforms))


(defn- xform [with-xforms where-xform return-xform]
  (if (some? where-xform)
    (if (seq with-xforms)
      (if (some? return-xform)
        (fn [context resource scope]
          (comp
            (where-xform context resource scope)
            (comp-xforms context resource scope with-xforms)
            (return-xform context resource scope)))
        (fn [context resource scope]
          (comp
            (where-xform context resource scope)
            (comp-xforms context resource scope with-xforms))))
      (if (some? return-xform)
        (fn [context resource scope]
          (comp
            (where-xform context resource scope)
            (return-xform context resource scope)))
        where-xform))
    (if (seq with-xforms)
      (if (some? return-xform)
        (fn [context resource scope]
          (comp
            (comp-xforms context resource scope with-xforms)
            (return-xform context resource scope)))
        (fn [context resource scope]
          (comp-xforms context resource scope with-xforms)))
      (if (some? return-xform)
        return-xform
        (fn [_ _ _] identity)))))


(defmulti compile-sort-by-item (fn [_ {:keys [type]}] type))


(defmethod compile-sort-by-item "ByExpression"
  [context sort-by-item]
  (update sort-by-item :expression #(compile context %)))


(defmethod compile-sort-by-item :default
  [_ sort-by-item]
  sort-by-item)


(defmulti hash-sort-by-item :type)


(defmethod hash-sort-by-item "ByExpression"
  [sort-by-item]
  (update sort-by-item :expression -hash))


(defmethod hash-sort-by-item :default
  [sort-by-item]
  sort-by-item)


(deftype AscComparator []
  Comparator
  (compare [_ x y]
    (let [less (p/less x y)]
      (cond
        (true? less) -1
        (false? less) 1
        (nil? x) -1
        (nil? y) 1
        :else 0))))


(def ^:private asc-comparator (->AscComparator))


(deftype DescComparator []
  Comparator
  (compare [_ x y]
    (let [less (p/less x y)]
      (cond
        (true? less) 1
        (false? less) -1
        (nil? x) 1
        (nil? y) -1
        :else 0))))


(def ^:private desc-comparator (->DescComparator))


(defn- comparator [direction]
  (if (#{"desc" "descending"} direction) desc-comparator asc-comparator))


(defmethod compile* :elm.compiler.type/query
  [{:keys [optimizations] :as context}
   {sources :source
    relationships :relationship
    :keys [where]
    {return :expression :keys [distinct] :or {distinct true}} :return
    {sort-by-items :by} :sort}]
  (if (= 1 (count sources))
    (let [{:keys [expression alias]} (first sources)
          with-equiv-clauses (filter #(= "WithEquiv" (:type %)) relationships)
          with-equiv-clauses (map #(compile-with-equiv-clause context %) with-equiv-clauses)
          with-xforms (map with-xform with-equiv-clauses)
          where-xform (some->> where (compile context) (where-xform alias))
          return-xform (some->> return (compile context) (return-xform alias))
          xform (xform with-xforms where-xform return-xform)
          sort-by-items (mapv #(compile-sort-by-item context %) sort-by-items)
          source (compile context expression)]
      (if (empty? sort-by-items)
        (if distinct
          (if (contains? optimizations :first)
            (reify Expression
              (-eval [_ context resource scope]
                (Eduction. (xform context resource scope) (-eval source context resource scope)))
              (-hash [_]
                (cond->
                  {:type :query
                   :source (-hash source)
                   :distinct true}
                  (some? where) (assoc :where (-hash where))
                  (seq with-equiv-clauses) (assoc :with (-hash with-equiv-clauses))
                  (some? return) (assoc :return (-hash return)))))
            (reify Expression
              (-eval [_ context resource scope]
                (vec (into #{} (xform context resource scope) (-eval source context resource scope))))
              (-hash [_]
                (cond->
                  {:type :query
                   :source (-hash source)
                   :distinct true}
                  (some? where) (assoc :where (-hash where))
                  (seq with-equiv-clauses) (assoc :with (-hash with-equiv-clauses))
                  (some? return) (assoc :return (-hash return))))))
          (reify Expression
            (-eval [_ context resource scope]
              (into [] (xform context resource scope) (-eval source context resource scope)))
            (-hash [_]
              (cond->
                {:type :query
                 :source (-hash source)
                 :distinct false}
                (some? where) (assoc :where (-hash where))
                (seq with-equiv-clauses) (assoc :with (-hash with-equiv-clauses))
                (some? return) (assoc :return (-hash return))))))
        (if distinct
          (reify Expression
            (-eval [_ context resource scope]
              ;; TODO: build a comparator of all sort by items
              (->> (into #{} (xform context resource scope) (-eval source context resource scope))
                   (sort-by identity (comparator (:direction (first sort-by-items))))
                   (vec)))
            (-hash [_]
              (cond->
                {:type :query
                 :source (-hash source)
                 :distinct true
                 :sort-by-items (mapv hash-sort-by-item sort-by-items)}
                (some? where) (assoc :where (-hash where))
                (seq with-equiv-clauses) (assoc :with (-hash with-equiv-clauses))
                (some? return) (assoc :return (-hash return)))))
          (reify Expression
            (-eval [_ context resource scope]
              ;; TODO: build a comparator of all sort by items
              (->> (into [] (xform context resource scope) (-eval source context resource scope))
                   (sort-by identity (comparator (:direction (first sort-by-items))))
                   (vec)))
            (-hash [_]
              (cond->
                {:type :query
                 :source (-hash source)
                 :distinct false
                 :sort-by-items (mapv hash-sort-by-item sort-by-items)}
                (some? where) (assoc :where (-hash where))
                (seq with-equiv-clauses) (assoc :with (-hash with-equiv-clauses))
                (some? return) (assoc :return (-hash return))))))))
    (throw (Exception. (str "Unsupported number of " (count sources) " sources in query.")))))


;; 10.3. AliasRef
;;
;; The AliasRef expression allows for the reference of a specific source within
;; the context of a query.
(defmethod compile* :elm.compiler.type/alias-ref
  [{:life/keys [single-query-scope]} {:keys [name]}]
  (if (= single-query-scope name)
    (reify Expression
      (-eval [_ _ _ entity]
        entity)
      (-hash [_]
        {:type :alias-ref}))
    (reify Expression
      (-eval [_ _ _ query-context]
        (get query-context name))
      (-hash [_]
        {:type :alias-ref
         :name name}))))


;; 10.12. With
;;
;; The With clause restricts the elements of a given source to only those
;; elements that have elements in the related source that satisfy the suchThat
;; condition. This operation is known as a semi-join in database languages.
;; condition. This operation is known as a semi-join in database languages.
(defn- find-operand-with-alias
  "Finds the operand in `expression` that accesses entities with `alias`."
  [operands alias]
  (some #(when (= (:life/scopes %) #{alias}) %) operands))


(s/fdef compile-with-equiv-clause
  :args (s/cat :context ::compile-context :with-equiv-clause :elm.query.life/with-equiv)
  :ret fn?)

(defn compile-with-equiv-clause
  "We use the terms `lhs` and `rhs` for left-hand-side and right-hand-side of
  the semi-join here.

  Returns a function of `eval-context` and `resource`."
  {:arglists '([context with-equiv-clause])}
  [context {:keys [alias] rhs :expression equiv-operands :equivOperand
            such-that :suchThat}]
  (if-let [single-query-scope (:life/single-query-scope context)]
    (if-let [rhs-operand (find-operand-with-alias equiv-operands alias)]
      (if-let [lhs-operand (find-operand-with-alias equiv-operands single-query-scope)]
        (let [rhs (compile context rhs)
              rhs-operand (compile (assoc context :life/single-query-scope alias) rhs-operand)
              lhs-operand (compile context lhs-operand)
              such-that (some->> such-that (compile (dissoc context :life/single-query-scope)))]
          (fn create-with-clause [eval-context resource]
            (let [rhs (-eval rhs eval-context resource nil)
                  indexer #(-eval rhs-operand eval-context resource %)]
              (if (some? such-that)
                (let [index (group-by indexer rhs)]
                  (fn eval-with-clause [eval-context resource lhs-entity]
                    (when-let [rhs-entities (some->> (-eval lhs-operand eval-context resource lhs-entity) (get index))]
                      (some #(-eval such-that eval-context resource {single-query-scope lhs-entity alias %}) rhs-entities))))
                (let [index (into #{} (map indexer) rhs)]
                  (fn eval-with-clause [eval-context resource lhs-entity]
                    (some->> (-eval lhs-operand eval-context resource lhs-entity) (contains? index))))))))
        (throw (Exception. "Unsupported call without left-hand-side operand.")))
      (throw (Exception. "Unsupported call without right-hand-side operand.")))
    (throw (Exception. "Unsupported call without single query scope."))))


;; TODO 10.13. Without



;; 11. External Data

;; 11.1. Retrieve
;;
;; Implementation Note:
;;
;; The compiler generates a function with takes a context map with a required
;; :db and an optional :patient. The :db is the database from which the data
;; will be retrieved.
;;
;; The :patient-id can be optionally populated with a patient identifier to
;; retrieve only data from that patient. In case :patient is `nil`, data from
;; all patients will be retrieved.
;;
;; The :patient-id will be set if the evaluation context is `"Patient"`. It'll
;; be `nil` if the evaluation context is `"Unspecified"`.
(defn- reverse-subject-kw [eval-context data-type-name]
  (case eval-context
    "Patient" (keyword data-type-name "_subject")
    "Specimen" (keyword data-type-name "_specimen")))


(defmethod compile* :elm.compiler.type/retrieve
  [{:keys [db eval-context] :as context}
   {:keys [codes] code-property-name :codeProperty data-type :dataType
    :or {code-property-name "code"}}]
  (let [unspecified-eval-context? (= "Unspecified" eval-context)
        [_ data-type-name] (elm-util/parse-qualified-name data-type)]
    (if-let [codes (some->> codes (compile context))]
      (if unspecified-eval-context?
        (reify Expression
          (-eval [_ {:keys [db]} _ _]
            (cql/list-resource-by-code
              db data-type-name code-property-name (map :db/id codes)))
          (-hash [_]
            {:type :retrieve
             :context eval-context
             :data-type-name data-type-name
             :code-property-name code-property-name
             :codes (-hash codes)}))

        (let [[code & more] codes]
          (if (empty? more)
            (retrieve/single-code-expr
              db eval-context data-type-name code-property-name code)
            (retrieve/multiple-code-expr
              db eval-context data-type-name code-property-name codes))))

      (if unspecified-eval-context?
        (reify Expression
          (-eval [_ {:keys [db]} _ _]
            (datomic-util/list-resources db data-type-name))
          (-hash [_]
            {:type :retrieve
             :context eval-context
             :data-type-name data-type-name}))

        (if (= data-type-name eval-context)
          (reify Expression
            (-eval [_ _ resource _]
              [resource])
            (-hash [_]
              {:type :retrieve
               :context eval-context}))

          ;; TODO: use https://www.hl7.org/fhir/compartmentdefinition-patient.html
          (let [reverse-subject-kw (reverse-subject-kw eval-context data-type-name)]
            (reify Expression
              (-eval [_ _ resource _]
                (reverse-subject-kw resource))
              (-hash [_]
                {:type :retrieve
                 :context eval-context
                 :reverse-subject-kw reverse-subject-kw}))))))))



;; 12. Comparison Operators

;; 12.1. Equal
(defbinop equal [operand-1 operand-2]
  (p/equal operand-1 operand-2))


;; 12.2. Equivalent
(defbinop equivalent [operand-1 operand-2]
  (p/equivalent operand-1 operand-2))


;; 12.3. Greater
(defbinop greater [operand-1 operand-2]
  (p/greater operand-1 operand-2))


;; 12.4. GreaterOrEqual
(defbinop greater-or-equal [operand-1 operand-2]
  (p/greater-or-equal operand-1 operand-2))


;; 12.5. Less
(defbinop less [operand-1 operand-2]
  (p/less operand-1 operand-2))


;; 12.6. LessOrEqual
(defbinop less-or-equal [operand-1 operand-2]
  (p/less-or-equal operand-1 operand-2))


;; 12.7. NotEqual
(defmethod compile* :elm.compiler.type/not-equal
  [_ _]
  (throw (Exception. "Unsupported NotEqual expression. Please normalize the ELM tree before compiling.")))


;; 13. Logical Operators

;; 13.1. And
(defmethod compile* :elm.compiler.type/and
  [context {[operand-1 operand-2] :operand}]
  (let [operand-1 (compile context operand-1)
        operand-2 (compile context operand-2)]
    (reify Expression
      (-eval [_ context resource scope]
        (let [operand-1 (-eval operand-1 context resource scope)]
          (if (false? operand-1)
            false
            (let [operand-2 (-eval operand-2 context resource scope)]
              (cond
                (false? operand-2) false
                (and (true? operand-1) (true? operand-2)) true)))))
      (-hash [_]
        {:type :and
         :operands [(-hash operand-1) (-hash operand-2)]}))))


;; 13.2 Implies
(defmethod compile* :elm.compiler.type/implies
  [_ _]
  (throw (Exception. "Unsupported Implies expression. Please normalize the ELM tree before compiling.")))


;; 13.3 Not
(defunop not [operand]
  (cond
    (true? operand) false
    (false? operand) true))


;; 13.4. Or
(defmethod compile* :elm.compiler.type/or
  [context {[operand-1 operand-2] :operand}]
  (let [operand-1 (compile context operand-1)
        operand-2 (compile context operand-2)]
    (reify Expression
      (-eval [_ context resource scope]
        (let [operand-1 (-eval operand-1 context resource scope)]
          (if (true? operand-1)
            true
            (let [operand-2 (-eval operand-2 context resource scope)]
              (cond
                (true? operand-2) true
                (and (false? operand-1) (false? operand-2)) false)))))
      (-hash [_]
        {:type :and
         :operands [(-hash operand-1) (-hash operand-2)]}))))


;; 13.5 Xor
(defbinop xor [a b]
  (cond
    (or (and (true? a) (true? b)) (and (false? a) (false? b))) false
    (or (and (true? a) (false? b)) (and (false? a) (true? b))) true))



;; 14. Nullological Operators

;; 14.1. Null
(defmethod compile* :elm.compiler.type/null
  [_ _])


;; 14.2. Coalesce
;;
;; The Coalesce operator returns the first non-null result in a list of
;; arguments. If all arguments evaluate to null, the result is null.
(defmethod compile* :elm.compiler.type/coalesce
  [context {operands :operand}]
  (condp = (count operands)
    1
    (let [operand (first operands)]
      (if (= "List" (:type operand))
        (let [operand (compile context operand)]
          (reify Expression
            (-eval [_ context resource scope]
              (reduce
                (fn [_ elem]
                  (let [elem (-eval elem context resource scope)]
                    (when (some? elem)
                      (reduced elem))))
                nil
                (-eval operand context resource scope)))
            (-hash [_]
              {:type :coalesce
               :operands (mapv -hash operands)})))
        (let [operand (compile context operand)]
          (reify Expression
            (-eval [_ context resource scope]
              (-eval operand context resource scope))
            (-hash [_]
              {:type :coalesce
               :operands (mapv -hash operands)})))))
    (let [operands (mapv #(compile context %) operands)]
      (reify Expression
        (-eval [_ context resource scope]
          (reduce
            (fn [_ operand]
              (let [operand (-eval operand context resource scope)]
                (when (some? operand)
                  (reduced operand))))
            nil
            operands))
        (-hash [_]
          {:type :coalesce
           :operands (mapv -hash operands)})))))


;; 14.3. IsFalse
(defunop is-false [operand]
  (false? operand))


;; 14.4. IsNull
(defunop is-null [operand]
  (nil? operand))


;; 14.5. IsTrue
(defunop is-true [operand]
  (true? operand))


;; 15. Conditional Operators

;; 15.1. Case
(defmethod compile* :elm.compiler.type/case
  [context {:keys [comparand else] items :caseItem}]
  (let [comparand (some->> comparand (compile context))
        items (mapv #(-> % (update :when (partial compile context))
                         (update :then (partial compile context)))
                    items)
        else (compile context else)]
    (if comparand
      (reify Expression
        (-eval [_ context resource scope]
          (let [comparand (-eval comparand context resource scope)]
            (loop [[{:keys [when then]} & next-items] items]
              (if (p/equal comparand (-eval when context resource scope))
                (-eval then context resource scope)
                (if (empty? next-items)
                  (-eval else context resource scope)
                  (recur next-items))))))
        (-hash [_]
          (cond->
            {:type :case
             :items (mapv #(-> % (update :when -hash) (update :then -hash)) items)
             :else (-hash else)}
            comparand
            (assoc :comparand (-hash comparand)))))
      (reify Expression
        (-eval [_ context resource scope]
          (loop [[{:keys [when then]} & next-items] items]
            (if (-eval when context resource scope)
              (-eval then context resource scope)
              (if (empty? next-items)
                (-eval else context resource scope)
                (recur next-items)))))
        (-hash [_]
          (cond->
            {:type :case
             :items (mapv #(-> % (update :when -hash) (update :then -hash)) items)
             :else (-hash else)}
            comparand
            (assoc :comparand (-hash comparand))))))))


;; 15.2. If
(defmethod compile* :elm.compiler.type/if
  [context {:keys [condition then else]}]
  (let [condition (compile context condition)
        then (compile context then)
        else (compile context else)]
    (reify Expression
      (-eval [_ context resource scope]
        (if (-eval condition context resource scope)
          (-eval then context resource scope)
          (-eval else context resource scope)))
      (-hash [_]
        {:type :if
         :condition (-hash condition)
         :then (-hash then)
         :else (-hash else)}))))



;; 16. Arithmetic Operators

;; 16.1. Abs
(defunop abs [x]
  (p/abs x))


;; 16.2. Add
(defbinop add [x y]
  (p/add x y))


;; 16.3. Ceiling
(defunop ceiling [x]
  (p/ceiling x))


;; 16.4. Divide
(defbinop divide [x y]
  (p/divide x y))


;; 16.5. Exp
(defunop exp [x]
  (p/exp x))


;; 16.6. Floor
(defunop floor [x]
  (p/floor x))


;; 16.7. Log
(defbinop log [x base]
  (p/log x base))


;; 16.8. Ln
(defunop ln [x]
  (p/ln x))


;; 16.9. MaxValue
(defn max-value [type]
  (let [[ns name] (elm-util/parse-qualified-name type)]
    (case ns
      "urn:hl7-org:elm-types:r1"
      (case name
        "Integer" (long Integer/MAX_VALUE)
        "Decimal" decimal/max
        "Date" date-time/max-date
        "DateTime" date-time/max-date-time
        "Time" date-time/max-time
        (throw (ex-info (str "Unsupported type `" name "` for MaxValue.")
                        {:type type})))
      (throw (ex-info (str "Unknown type namespace `" ns "`.") {:type type})))))


(defmethod compile* :elm.compiler.type/max-value
  [_ {type :valueType}]
  (max-value type))


;; 16.10. MinValue
(defn min-value [type]
  (let [[ns name] (elm-util/parse-qualified-name type)]
    (case ns
      "urn:hl7-org:elm-types:r1"
      (case name
        "Integer" (long Integer/MIN_VALUE)
        "Decimal" decimal/min
        "Date" date-time/min-date
        "DateTime" date-time/min-date-time
        "Time" date-time/min-time
        (throw (ex-info (str "Unsupported type `" name "` for MinValue.")
                        {:type type})))
      (throw (ex-info (str "Unknown type namespace `" ns "`.") {:type type})))))


(defmethod compile* :elm.compiler.type/min-value
  [_ {type :valueType}]
  (min-value type))


;; 16.11. Modulo
(defbinop modulo [num div]
  (p/modulo num div))


;; 16.12. Multiply
(defbinop multiply [x y]
  (p/multiply x y))


;; 16.13. Negate
(defunop negate [x]
  (p/negate x))


;; 16.14. Power
(defbinop power [x exp]
  (p/power x exp))


;; 16.15. Predecessor
(defunop predecessor [x]
  (p/predecessor x))


;; 16.16. Round
(defmethod compile* :elm.compiler.type/round
  [context {:keys [operand precision]}]
  (let [operand (compile context operand)
        precision (some->> precision (compile context))]
    (cond
      (or (nil? precision) (and (number? precision) (zero? precision)))
      (reify Expression
        (-eval [_ context resource scope]
          (p/round (-eval operand context resource scope) 0))
        (-hash [_]
          {:type :round
           :operand (-hash operand)}))

      (number? precision)
      (reify Expression
        (-eval [_ context resource scope]
          (p/round (-eval operand context resource scope) precision))
        (-hash [_]
          {:type :round
           :operand (-hash operand)
           :precision (-hash precision)}))

      :else
      (reify Expression
        (-eval [_ context resource scope]
          (p/round (-eval operand context resource scope)
                   (-eval precision context resource scope)))
        (-hash [_]
          {:type :round
           :operand (-hash operand)
           :precision (-hash precision)})))))


;; 16.17. Subtract
(defbinop subtract [x y]
  (p/subtract x y))


;; 16.18. Successor
(defunop successor [x]
  (p/successor x))


;; 16.19. Truncate
(defunop truncate [x]
  (p/truncate x))


;; 16.20. TruncatedDivide
(defbinop truncated-divide [num div]
  (p/truncated-divide num div))



;; 17. String Operators

;; 17.1. Combine
(defmethod compile* :elm.compiler.type/combine
  [context {:keys [source separator]}]
  (let [source (compile context source)
        separator (some->> separator (compile context))]
    (if separator
      (reify Expression
        (-eval [_ context resource scope]
          (when-let [source (-eval source context resource scope)]
            (string/combine (-eval separator context resource scope) source)))
        (-hash [_]
          {:type :combine
           :source (-hash source)
           :separator (-hash separator)}))
      (reify Expression
        (-eval [_ context resource scope]
          (when-let [source (-eval source context resource scope)]
            (string/combine source)))
        (-hash [_]
          {:type :combine
           :source (-hash source)})))))


;; 17.2. Concatenate
(defnaryop concatenate [strings]
  (string/combine strings))


;; 17.3. EndsWith
(defbinop ends-with [s suffix]
  (when (and s suffix)
    (str/ends-with? s suffix)))


;; 17.6. Indexer
(defbinop indexer [x index]
  (p/indexer x index))


;; 17.7. LastPositionOf
(defmethod compile* :elm.compiler.type/last-position-of
  [context {:keys [pattern string]}]
  (let [pattern (compile context pattern)
        string (compile context string)]
    (reify Expression
      (-eval [_ context resource scope]
        (when-let [^String pattern (-eval pattern context resource scope)]
          (when-let [^String string (-eval string context resource scope)]
            (.lastIndexOf string pattern))))
      (-hash [_]
        {:type :last-position-of
         :pattern (-hash pattern)
         :string (-hash string)}))))


;; 17.8. Length
(defunop length [x]
  (count x))


;; 17.9. Lower
(defunop lower [s]
  (some-> s str/lower-case))


;; 17.10. Matches
(defbinop matches [s pattern]
  (when (and s pattern)
    (some? (re-matches (re-pattern pattern) s))))


;; 17.12. PositionOf
(defmethod compile* :elm.compiler.type/position-of
  [context {:keys [pattern string]}]
  (let [pattern (compile context pattern)
        string (compile context string)]
    (reify Expression
      (-eval [_ context resource scope]
        (when-let [^String pattern (-eval pattern context resource scope)]
          (when-let [^String string (-eval string context resource scope)]
            (.indexOf string pattern))))
      (-hash [_]
        {:type :last-position-of
         :pattern (-hash pattern)
         :string (-hash string)}))))


;; 17.13. ReplaceMatches
(defternop replace-matches [s pattern substitution]
  (when (and s pattern substitution)
    (str/replace s (re-pattern pattern) substitution)))


;; 17.14. Split
(defmethod compile* :elm.compiler.type/split
  [context {string :stringToSplit :keys [separator]}]
  (let [string (compile context string)
        separator (some->> separator (compile context))]
    (if separator
      (reify Expression
        (-eval [_ context resource scope]
          (when-let [string (-eval string context resource scope)]
            (if (= "" string)
              [string]
              (if-let [separator (-eval separator context resource scope)]
                (condp = (count separator)
                  1
                  (loop [[char & more] string
                         result []
                         acc (StringBuilder.)]
                    (if (= (str char) separator)
                      (if more
                        (recur more (conj result (str acc)) (StringBuilder.))
                        (conj result (str acc)))
                      (if more
                        (recur more result (.append acc char))
                        (conj result (str (.append acc char))))))
                  ;; TODO: implement split with more than one char.
                  (throw (Exception. "TODO: implement split with more than one char.")))
                [string]))))
        (-hash [_]
          {:type :combine
           :string (-hash string)
           :separator (-hash separator)}))
      (reify Expression
        (-eval [_ context resource scope]
          (when-let [string (-eval string context resource scope)]
            [string]))
        (-hash [_]
          {:type :combine
           :string (-hash string)})))))


;; 17.16. StartsWith
(defbinop starts-with [s prefix]
  (when (and s prefix)
    (str/starts-with? s prefix)))


;; 17.17. Substring
(defmethod compile* :elm.compiler.type/substring
  [context {string :stringToSub start-index :startIndex :keys [length]}]
  (let [string (compile context string)
        start-index (compile context start-index)
        length (some->> length (compile context))]
    (if length
      (reify Expression
        (-eval [_ context resource scope]
          (when-let [^String string (-eval string context resource scope)]
            (when-let [start-index (-eval start-index context resource scope)]
              (when (and (<= 0 start-index) (< start-index (count string)))
                (subs string start-index (min (+ start-index length)
                                              (count string)))))))
        (-hash [_]
          {:type :last-position-of
           :string (-hash string)
           :start-index (-hash start-index)
           :length (-hash length)}))
      (reify Expression
        (-eval [_ context resource scope]
          (when-let [^String string (-eval string context resource scope)]
            (when-let [start-index (-eval start-index context resource scope)]
              (when (and (<= 0 start-index) (< start-index (count string)))
                (subs string start-index)))))
        (-hash [_]
          {:type :last-position-of
           :string (-hash string)
           :start-index (-hash start-index)})))))


;; 17.18. Upper
(defunop upper [s]
  (some-> s str/upper-case))



;; 18. Date and Time Operators

(defn- to-year [year]
  (when-not (< 0 year 10000)
    (throw (Exception. (str "Year `" year "` out of range."))))
  (Year/of year))


(defn- to-month [year month]
  (when-not (< 0 year 10000)
    (throw (Exception. (str "Year `" year "` out of range."))))
  (YearMonth/of ^long year ^long month))


(defn- to-day [year month day]
  (when-not (< 0 year 10000)
    (throw (Exception. (str "Year `" year "` out of range."))))
  (LocalDate/of ^long year ^long month ^long day))


(defn- to-local-date-time
  [year month day hour minute second millisecond]
  (when-not (< 0 year 10000)
    (throw (Exception. (str "Year `" year "` out of range."))))
  (LocalDateTime/of ^long year ^long month ^long day
                    ^long hour ^long minute ^long second
                    ^long (* 1000000 millisecond)))


(defn- to-local-date-time-with-offset
  "Creates a DateTime with a local date time adjusted for the offset of the
  evaluation request."
  [now year month day hour minute second millisecond timezone-offset]
  (when-not (< 0 year 10000)
    (throw (Exception. (str "Year `" year "` out of range."))))
  (-> (OffsetDateTime/of ^long year ^long month ^long day ^long hour
                         ^long minute ^long second (* 1000000 millisecond)
                         (ZoneOffset/ofTotalSeconds (* timezone-offset 3600)))
      (.withOffsetSameInstant (.getOffset ^OffsetDateTime now))
      (.toLocalDateTime)))


;; 18.6. Date
(defmethod compile* :elm.compiler.type/date
  [context {:keys [year month day]}]
  (let [year (some->> year (compile context))
        month (some->> month (compile context))
        day (some->> day (compile context))]
    (cond
      (and (int? day) (int? month) (int? year))
      (to-day year month day)

      (some? day)
      (reify Expression
        (-eval [_ context resource scope]
          (to-day (-eval year context resource scope)
                  (-eval month context resource scope)
                  (-eval day context resource scope)))
        (-hash [_]
          {:type :date
           :year (-hash year)
           :month (-hash month)
           :day (-hash day)}))

      (and (int? month) (int? year))
      (to-month year month)

      (some? month)
      (reify Expression
        (-eval [_ context resource scope]
          (to-month (-eval year context resource scope)
                    (-eval month context resource scope)))
        (-hash [_]
          {:type :date
           :year (-hash year)
           :month (-hash month)}))

      (int? year)
      (some-> year to-year)

      :else
      (reify Expression
        (-eval [_ context resource scope]
          (some-> (-eval year context resource scope) to-year))
        (-hash [_]
          {:type :date
           :year (-hash year)})))))


;; 18.7. DateFrom
(defunop date-from [x]
  (p/date-from x))


;; 18.8. DateTime
(defmethod compile* :elm.compiler.type/date-time
  [context {:keys [year month day hour minute second millisecond]
            timezone-offset :timezoneOffset
            :as expression}]
  (let [year (some->> year (compile context))
        month (some->> month (compile context))
        day (some->> day (compile context))
        hour (some->> hour (compile context))
        minute (or (some->> minute (compile context)) 0)
        second (or (some->> second (compile context)) 0)
        millisecond (or (some->> millisecond (compile context)) 0)
        timezone-offset (some->> timezone-offset (compile context))]
    (cond
      (number? timezone-offset)
      (cond
        (and (int? millisecond) (int? second) (int? minute) (int? hour)
             (int? day) (int? month) (int? year))
        (reify Expression
          (-eval [_ {:keys [now]} _ _]
            (to-local-date-time-with-offset
              now year month day hour minute second millisecond timezone-offset))
          (-hash [_]
            {:type :date-time
             :year (-hash year)
             :month (-hash month)
             :day (-hash day)
             :hour (-hash hour)
             :minute (-hash minute)
             :second (-hash second)
             :millisecond (-hash millisecond)
             :timezone-offset (-hash timezone-offset)}))

        (some? hour)
        (reify Expression
          (-eval [_ {:keys [now] :as context} resource scope]
            (to-local-date-time-with-offset
              now
              (-eval year context resource scope)
              (-eval month context resource scope)
              (-eval day context resource scope)
              (-eval hour context resource scope)
              (or (-eval minute context resource scope) 0)
              (or (-eval second context resource scope) 0)
              (or (-eval millisecond context resource scope) 0)
              timezone-offset))
          (-hash [_]
            {:type :date-time
             :year (-hash year)
             :month (-hash month)
             :day (-hash day)
             :hour (-hash hour)
             :minute (-hash minute)
             :second (-hash second)
             :millisecond (-hash millisecond)
             :timezone-offset (-hash timezone-offset)}))

        :else
        (throw (ex-info "Need at least an hour if timezone offset is given."
                        {:expression expression})))

      (some? timezone-offset)
      (cond
        (some? hour)
        (reify Expression
          (-eval [_ {:keys [now] :as context} resource scope]
            (to-local-date-time-with-offset
              now
              (-eval year context resource scope)
              (-eval month context resource scope)
              (-eval day context resource scope)
              (-eval hour context resource scope)
              (or (-eval minute context resource scope) 0)
              (or (-eval second context resource scope) 0)
              (or (-eval millisecond context resource scope) 0)
              (-eval timezone-offset context resource scope)))
          (-hash [_]
            {:type :date-time
             :year (-hash year)
             :month (-hash month)
             :day (-hash day)
             :hour (-hash hour)
             :minute (-hash minute)
             :second (-hash second)
             :timezone-offset (-hash timezone-offset)}))

        :else
        (throw (ex-info "Need at least an hour if timezone offset is given."
                        {:expression expression})))

      :else
      (cond
        (and (int? millisecond) (int? second) (int? minute) (int? hour)
             (int? day) (int? month) (int? year))
        (to-local-date-time year month day hour minute second millisecond)

        (some? hour)
        (reify Expression
          (-eval [_ context resource scope]
            (to-local-date-time
              (-eval year context resource scope)
              (-eval month context resource scope)
              (-eval day context resource scope)
              (-eval hour context resource scope)
              (or (-eval minute context resource scope) 0)
              (or (-eval second context resource scope) 0)
              (or (-eval millisecond context resource scope) 0)))
          (-hash [_]
            {:type :date-time
             :year (-hash year)
             :month (-hash month)
             :day (-hash day)
             :hour (-hash hour)
             :minute (-hash minute)
             :second (-hash second)
             :millisecond (-hash millisecond)}))

        (and (int? day) (int? month) (int? year))
        (to-day year month day)

        (some? day)
        (reify Expression
          (-eval [_ context resource scope]
            (to-day (-eval year context resource scope)
                    (-eval month context resource scope)
                    (-eval day context resource scope)))
          (-hash [_]
            {:type :date
             :year (-hash year)
             :month (-hash month)
             :day (-hash day)}))

        (and (int? month) (int? year))
        (to-month year month)

        (some? month)
        (reify Expression
          (-eval [_ context resource scope]
            (to-month (-eval year context resource scope)
                      (-eval month context resource scope)))
          (-hash [_]
            {:type :date
             :year (-hash year)
             :month (-hash month)}))

        (int? year)
        (some-> year to-year)

        :else
        (reify Expression
          (-eval [_ context resource scope]
            (some-> (-eval year context resource scope) to-year))
          (-hash [_]
            {:type :date
             :year (-hash year)}))))))


;; 18.9. DateTimeComponentFrom
(defunopp date-time-component-from [x precision]
  (p/date-time-component-from x precision))


;; 18.10. DifferenceBetween
(defbinopp difference-between [operand-1 operand-2 precision]
  (p/difference-between operand-1 operand-2 precision))


;; 18.11. DurationBetween
(defbinopp duration-between [operand-1 operand-2 precision]
  (p/duration-between operand-1 operand-2 precision))


;; 18.13. Now
(defmethod compile* :elm.compiler.type/now
  [_ _]
  (reify Expression
    (-eval [_ {:keys [now]} _ _]
      now)
    (-hash [_]
      {:type :now})))


;; 18.14. SameAs
(defbinopp same-as [x y precision]
  (p/same-as x y precision))


;; 18.15. SameOrBefore
(defbinopp same-or-before [x y precision]
  (p/same-or-before x y precision))


;; 18.16. SameOrAfter
(defbinopp same-or-after [x y precision]
  (p/same-or-after x y precision))


;; 18.18. Time
(defmethod compile* :elm.compiler.type/time
  [context {:keys [hour minute second millisecond]}]
  (let [hour (some->> hour (compile context))
        minute (some->> minute (compile context))
        second (some->> second (compile context))
        millisecond (some->> millisecond (compile context))]
    (cond
      (and (int? millisecond) (int? second) (int? minute) (int? hour))
      (local-time hour minute second millisecond)

      (some? millisecond)
      (reify Expression
        (-eval [_ context resource scope]
          (local-time (-eval hour context resource scope)
                      (-eval minute context resource scope)
                      (-eval second context resource scope)
                      (-eval millisecond context resource scope)))
        (-hash [_]
          {:type :time
           :hour (-hash hour)
           :minute (-hash minute)
           :second (-hash second)
           :millisecond (-hash millisecond)}))

      (and (int? second) (int? minute) (int? hour))
      (local-time hour minute second)

      (some? second)
      (reify Expression
        (-eval [_ context resource scope]
          (local-time (-eval hour context resource scope)
                      (-eval minute context resource scope)
                      (-eval second context resource scope)))
        (-hash [_]
          {:type :time
           :hour (-hash hour)
           :minute (-hash minute)
           :second (-hash second)}))

      (and (int? minute) (int? hour))
      (local-time hour minute)

      (some? minute)
      (reify Expression
        (-eval [_ context resource scope]
          (local-time (-eval hour context resource scope)
                      (-eval minute context resource scope)))
        (-hash [_]
          {:type :time
           :hour (-hash hour)
           :minute (-hash minute)}))

      (int? hour)
      (local-time hour)

      :else
      (reify Expression
        (-eval [_ context resource scope]
          (local-time (-eval hour context resource scope)))
        (-hash [_]
          {:type :time
           :hour (-hash hour)})))))


;; 18.21. TimeOfDay
(defmethod compile* :elm.compiler.type/time-of-day
  [_ _]
  (reify Expression
    (-eval [_ {:keys [now]} _ _]
      (.toLocalTime ^OffsetDateTime now))
    (-hash [_]
      {:type :time-of-day})))


;; 18.22. Today
(defmethod compile* :elm.compiler.type/today
  [_ _]
  (reify Expression
    (-eval [_ {:keys [now]} _ _]
      (.toLocalDate ^OffsetDateTime now))
    (-hash [_]
      {:type :today})))



;; 19. Interval Operators

;; 19.1. Interval
(defn- determine-type [{:keys [resultTypeName asType]}]
  (or resultTypeName asType))

(defmethod compile* :elm.compiler.type/interval
  [context {:keys [low high]
            low-closed-expression :lowClosedExpression
            high-closed-expression :highClosedExpression
            low-closed :lowClosed
            high-closed :highClosed
            :or {low-closed true high-closed true}}]
  (let [type (determine-type low)
        low (some->> low (compile context))
        high (some->> high (compile context))
        low-closed-expression (some->> low-closed-expression (compile context))
        high-closed-expression (some->> high-closed-expression (compile context))]
    (assert (string? type) (prn-str low))
    (reify Expression
      (-eval [_ context resource scope]
        (let [low (-eval low context resource scope)
              high (-eval high context resource scope)
              low-closed (or (-eval low-closed-expression context resource scope) low-closed)
              high-closed (or (-eval high-closed-expression context resource scope) high-closed)]
          (interval
            (if low-closed
              (if (nil? low)
                (min-value type)
                low)
              (p/successor low))
            (if high-closed
              (if (nil? high)
                (max-value type)
                high)
              (p/predecessor high)))))
      (-hash [_]
        (cond->
          {:type :interval}
          low
          (assoc :low (-hash low))
          high
          (assoc :high (-hash high))
          low-closed-expression
          (assoc :low-closed-expression (-hash low-closed-expression))
          high-closed-expression
          (assoc :high-closed-expression (-hash high-closed-expression))
          low-closed
          (assoc :low-closed low-closed)
          high-closed
          (assoc :high-closed high-closed))))))


;; 19.2. After
(defbinopp after [operand-1 operand-2 precision]
  (p/after operand-1 operand-2 precision))


;; 19.3. Before
(defbinopp before [operand-1 operand-2 precision]
  (p/before operand-1 operand-2 precision))


;; 19.4. Collapse
(defbinop collapse [source per]
  (when source
    (let [source (sort-by :start (remove nil? source))]
      (reverse
        (reduce
          (fn [r right]
            (let [[left & others] r]
              (if (p/greater-or-equal (:end left) (p/predecessor (:start right)))
                (cons (interval (:start left) (:end right)) others)
                (cons right r))))
          (cond-> (list) (first source) (conj (first source)))
          (rest source))))))


;; 19.5. Contains
(defbinopp contains [list-or-interval x precision]
  (p/contains list-or-interval x precision))


;; 19.6. End
(defunop end [{:keys [end]}]
  end)


;; 19.7. Ends
(defbinop ends [x y _]
  (and (p/greater-or-equal (:start x) (:start y))
       (p/equal (:end x) (:end y))))


;; 19.10. Except
(defbinop except [x y]
  (p/except x y))


;; 19.12. In
(defmethod compile* :elm.compiler.type/in
  [_ _]
  (throw (Exception. "Unsupported In expression. Please normalize the ELM tree before compiling.")))


;; 19.13. Includes
(defbinopp includes [x y precision]
  (p/includes x y precision))


;; 19.14. IncludedIn
(defmethod compile* :elm.compiler.type/included-in
  [_ _]
  (throw (Exception. "Unsupported IncludedIn expression. Please normalize the ELM tree before compiling.")))


;; 19.15. Intersect
(defbinop intersect [a b]
  (p/intersect a b))


;; 19.16. Meets
(defmethod compile* :elm.compiler.type/meets
  [_ _]
  (throw (Exception. "Unsupported Meets expression. Please normalize the ELM tree before compiling.")))


;; 19.17. MeetsBefore
(defbinopp meets-before [x y _]
  (p/equal (:end x) (p/predecessor (:start y))))


;; 19.18. MeetsAfter
(defbinopp meets-after [x y _]
  (p/equal (:start x) (p/successor (:end y))))


;; 19.20. Overlaps
(defmethod compile* :elm.compiler.type/overlaps
  [_ _]
  (throw (Exception. "Unsupported Overlaps expression. Please normalize the ELM tree before compiling.")))


;; 19.21. OverlapsBefore
(defmethod compile* :elm.compiler.type/overlaps-before
  [_ _]
  (throw (Exception. "Unsupported OverlapsBefore expression. Please normalize the ELM tree before compiling.")))


;; 19.22. OverlapsAfter
(defmethod compile* :elm.compiler.type/overlaps-after
  [_ _]
  (throw (Exception. "Unsupported OverlapsAfter expression. Please normalize the ELM tree before compiling.")))


;; 19.23. PointFrom
(defunop point-from [interval {{:keys [locator]} :operand :as expression}]
  (when interval
    (if (p/equal (:start interval) (:end interval))
      (:start interval)
      (throw (ex-info (append-locator "Invalid non-unit interval in `PointFrom` expression at" locator)
                      {:expression expression})))))


;; 19.24. ProperContains
(defbinopp proper-contains [list-or-interval x precision]
  (p/proper-contains list-or-interval x precision))


;; 19.25. ProperIn
(defmethod compile* :elm.compiler.type/proper-in
  [_ _]
  (throw (Exception. "Unsupported ProperIn expression. Please normalize the ELM tree before compiling.")))


;; 19.26. ProperIncludes
(defbinopp proper-includes [x y precision]
  (p/proper-includes x y precision))


;; 19.27. ProperIncludedIn
(defmethod compile* :elm.compiler.type/proper-included-in
  [_ _]
  (throw (Exception. "Unsupported ProperIncludedIn expression. Please normalize the ELM tree before compiling.")))


;; 19.29. Start
(defunop start [{:keys [start]}]
  start)


;; 19.30. Starts
(defbinop starts [x y _]
  (and (p/equal (:start x) (:start y))
       (p/less-or-equal (:end x) (:end y))))


;; 19.31. Union
(defbinop union [a b]
  (p/union a b))


;; 19.32. Width
(defunop width [{:keys [start end]}]
  (p/subtract end start))



;; 20. List Operators

;; 20.1. List
(defmethod compile* :elm.compiler.type/list
  [context {elements :element}]
  (let [elements (mapv #(compile context %) elements)]
    (reify Expression
      (-eval [_ context resource scope]
        (mapv #(-eval % context resource scope) elements))
      (-hash [_]
        {:type :list
         :elements (mapv -hash elements)}))))


;; 20.3. Current
(defmethod compile* :elm.compiler.type/current
  [_ {:keys [scope]}]
  (if scope
    (reify Expression
      (-eval [_ _ _ scopes]
        (get scopes scope))
      (-hash [_]
        {:type :current
         :scope scope}))
    (reify Expression
      (-eval [_ _ _ scope]
        scope)
      (-hash [_]
        {:type :current}))))


;; 20.4. Distinct
;;
;; TODO: implementation is O(n^2)
(defunop distinct [list]
  (when list
    (reduce
      (fn [result x]
        (if (p/contains result x nil)
          result
          (conj result x)))
      []
      list)))


;; 20.8. Exists
(defunop exists
  {:optimizations #{:first}}
  [list]
  (not (empty? list)))


;; 20.9. Filter
(defmethod compile* :elm.compiler.type/filter
  [context {:keys [source condition scope]}]
  (let [source (compile context source)
        condition (compile context condition)]
    (if scope
      (reify Expression
        (-eval [_ context resource scopes]
          (when-let [source (-eval source context resource scopes)]
            (filterv
              (fn [x]
                (-eval condition context resource (assoc scopes scope x)))
              source)))
        (-hash [_]
          {:type :filter
           :source (-hash source)
           :condition (-hash condition)}))
      (reify Expression
        (-eval [_ context resource scopes]
          (when-let [source (-eval source context resource scopes)]
            (filterv
              (fn [_]
                (-eval condition context resource scopes))
              source)))
        (-hash [_]
          {:type :filter
           :source (-hash source)
           :condition (-hash condition)})))))


;; 20.10. First
;;
;; TODO: orderBy
(defmethod compile* :elm.compiler.type/first
  [context {:keys [source]}]
  (let [source (compile context source)]
    (reify Expression
      (-eval [_ context resource scopes]
        (first (-eval source context resource scopes)))
      (-hash [_]
        {:type :first
         :source (-hash source)}))))


;; 20.11. Flatten
(defunop flatten [list]
  (when list
    (letfn [(flatten [to from]
              (reduce
                (fn [result x]
                  (if (sequential? x)
                    (flatten result x)
                    (conj result x)))
                to
                from))]
      (flatten [] list))))


;; 20.12. ForEach
(defmethod compile* :elm.compiler.type/for-each
  [context {:keys [source element scope]}]
  (let [source (compile context source)
        element (compile context element)]
    (if scope
      (reify Expression
        (-eval [_ context resource scopes]
          (when-let [source (-eval source context resource scopes)]
            (mapv
              (fn [x]
                (-eval element context resource (assoc scopes scope x)))
              source)))
        (-hash [_]
          {:type :filter
           :source (-hash source)
           :element (-hash element)}))
      (reify Expression
        (-eval [_ context resource scopes]
          (when-let [source (-eval source context resource scopes)]
            (mapv
              (fn [_]
                (-eval element context resource scopes))
              source)))
        (-hash [_]
          {:type :filter
           :source (-hash source)
           :element (-hash element)})))))


;; 20.16. IndexOf
(defmethod compile* :elm.compiler.type/index-of
  [context {:keys [source element]}]
  (let [source (compile context source)
        element (compile context element)]
    (reify Expression
      (-eval [_ context resource scopes]
        (when-let [source (-eval source context resource scopes)]
          (when-let [element (-eval element context resource scopes)]
            (or
              (first
                (keep-indexed
                  (fn [idx x]
                    (when
                      (p/equal element x)
                      idx))
                  source))
              -1))))
      (-hash [_]
        {:type :filter
         :source (-hash source)
         :element (-hash element)}))))


;; 20.18. Last
;;
;; TODO: orderBy
(defmethod compile* :elm.compiler.type/last
  [context {:keys [source]}]
  (let [source (compile context source)]
    (reify Expression
      (-eval [_ context resource scopes]
        (peek (-eval source context resource scopes)))
      (-hash [_]
        {:type :last
         :source (-hash source)}))))


;; 20.24. Repeat
;;
;; TODO: not implemented


;; 20.25. SingletonFrom
(defunop singleton-from [list {{:keys [locator]} :operand :as expression}]
  (cond
    (empty? list) nil
    (nil? (next list)) (first list)
    :else (throw (ex-info (append-locator "More than one element in expression `SingletonFrom` at" locator)
                          {:expression expression}))))


;; 20.26. Slice
(defmethod compile* :elm.compiler.type/slice
  [context {:keys [source] start-index :startIndex end-index :endIndex}]
  (let [source (compile context source)
        start-index (some->> start-index (compile context))
        end-index (some->> end-index (compile context))]
    (reify Expression
      (-eval [_ context resource scopes]
        (when-let [source (-eval source context resource scopes)]
          (let [start-index (or (-eval start-index context resource scopes) 0)
                end-index (or (-eval end-index context resource scopes) (count source))]
            (if (or (neg? start-index) (< end-index start-index))
              []
              (subvec
                source
                start-index
                end-index)))))
      (-hash [_]
        (cond->
          {:type :filter
           :source (-hash source)
           :start-index (-hash start-index)}
          end-index
          (assoc :end-index (-hash end-index)))))))


;; 20.27. Sort
(defmethod compile* :elm.compiler.type/sort
  [context {:keys [source] sort-by-items :by}]
  (let [source (compile context source)
        sort-by-items (mapv #(compile-sort-by-item context %) sort-by-items)]
    (reduce
      (fn [source {:keys [type direction]}]
        (case type
          "ByDirection"
          (let [comp (comparator direction)]
            (reify Expression
              (-eval [_ context resource scopes]
                (when-let [source (-eval source context resource scopes)]
                  (sort-by identity comp source)))
              (-hash [_]
                {:type :filter
                 :source (-hash source)
                 :sort-by-items (mapv hash-sort-by-item sort-by-items)})))))
      source
      sort-by-items)))



;; 21. Aggregate Operators

;; 21.1. AllTrue
(defaggop all-true [source]
  (reduce #(if (false? %2) (reduced %2) %1) true source))


;; 21.2. AnyTrue
(defaggop any-true [source]
  (reduce #(if (true? %2) (reduced %2) %1) false source))


;; 21.3. Avg
(defaggop avg [source]
  (transduce identity aggregates/avg-reducer source))


;; 21.4. Count
(defaggop count [source]
  (transduce identity aggregates/count-reducer source))


;; 21.5. GeometricMean
(defaggop geometric-mean [source]
  (transduce identity aggregates/geometric-mean-reducer source))


;; 21.6. Product
(defaggop product [source]
  (transduce identity aggregates/product-reducer source))


;; 21.7. Max
(defaggop max [source]
  (transduce identity aggregates/max-reducer source))


;; 21.8. Median
(defaggop median [source]
  (let [sorted (vec (sort-by identity asc-comparator (remove nil? source)))]
    (when (seq sorted)
      (if (zero? (rem (count sorted) 2))
        (let [upper-idx (quot (count sorted) 2)]
          (p/divide (p/add (nth sorted (dec upper-idx))
                           (nth sorted upper-idx))
                    2))
        (nth sorted (quot (count sorted) 2))))))


;; 21.9. Min
(defaggop min [source]
  (transduce identity aggregates/min-reducer source))


;; 21.10. Mode
(defaggop mode [source]
  (transduce identity aggregates/max-freq-reducer (frequencies (remove nil? source))))


;; 21.11. PopulationVariance
(defaggop population-variance [source]
  (transduce identity aggregates/population-variance-reducer source))


;; 21.12. PopulationStdDev
(defaggop population-std-dev [source]
  (p/power (transduce identity aggregates/population-variance-reducer source) 0.5M))


;; 21.13. Sum
(defaggop sum [source]
  (transduce identity aggregates/sum-reducer source))


;; 21.14. StdDev
(defaggop std-dev [source]
  (p/power (transduce identity aggregates/variance-reducer source) 0.5M))


;; 21.15. Variance
(defaggop variance [source]
  (transduce identity aggregates/variance-reducer source))



;; 22. Type Operators

;; 22.1. As
;;
;; The As operator allows the result of an expression to be cast as a given
;; target type. This allows expressions to be written that are statically typed
;; against the expected run-time type of the argument. If the argument is not of
;; the specified type, and the strict attribute is false (the default), the
;; result is null. If the argument is not of the specified type and the strict
;; attribute is true, an exception is thrown.
(defmethod compile* :elm.compiler.type/as
  [context {:keys [operand] as-type :asType as-type-specifier :asTypeSpecifier}]
  (let [as-type-specifier
        (or as-type-specifier {:type "NamedTypeSpecifier" :name as-type})]
    (compile context (assoc operand :life/as-type as-type-specifier))))

;; 22.2. CanConvert

;; 22.3. CanConvertQuantity

;; 22.4. Children

;; 22.5. Convert

;; 22.6. ConvertQuantity

;; 22.7. ConvertsToBoolean

;; 22.8. ConvertsToDate

;; 22.9. ConvertsToDateTime

;; 22.10. ConvertsToDecimal

;; 22.11. ConvertsToInteger

;; 22.12. ConvertsToQuantity

;; 22.13. ConvertsToRatio

;; 22.14. ConvertsToString

;; 22.15. ConvertsToTime

;; 22.16. Descendents
(defmethod compile* :elm.compiler.type/descendents
  [context {:keys [source]}]
  (let [source (compile context source)]
    (reify Expression
      (-eval [_ context resource scopes]
        ;;TODO: implement
        )
      (-hash [_]
        {:type :first
         :source (-hash source)}))))


;; 22.17. Is

;; 22.18. ToBoolean

;; 22.19. ToChars

;; 22.20. ToConcept

;; 22.21. ToDate
(defmethod compile* :elm.compiler.type/to-date
  [context {:keys [operand]}]
  (let [operand (compile context operand)]
    (reify Expression
      (-eval [_ {:keys [now] :as context} resource scope]
        (p/to-date (-eval operand context resource scope) now))
      (-hash [_]
        {:type :to-date
         :operand (-hash operand)}))))


;; 22.22. ToDateTime
(defmethod compile* :elm.compiler.type/to-date-time
  [context {:keys [operand]}]
  (let [operand (compile context operand)]
    (reify Expression
      (-eval [_ {:keys [now] :as context} resource scope]
        (p/to-date-time (-eval operand context resource scope) now))
      (-hash [_]
        {:type :to-date-time
         :operand (-hash operand)}))))


;; 22.23. ToDecimal
(defunop to-decimal [x]
  (p/to-decimal x))


;; 22.24. ToInteger
(defunop to-integer [x]
  (p/to-integer x))


;; 22.25. ToList
(defunop to-list [x]
  (if (nil? x) [] [x]))


;; 22.26. ToQuantity
(defunop to-quantity [x]
  (p/to-quantity x))


;; 22.28. ToString
(defunop to-string [x]
  (p/to-string x))



;; 23. Clinical Operators

;; 23.4. CalculateAgeAt
(defbinopp calculate-age-at [birth-date date precision]
  (p/duration-between birth-date date precision))
