(ns dromon.elm.compiler.property-test
  (:require
    [dromon.datomic.quantity :as datomic-quantity]
    [dromon.elm.compiler.property :refer [attr scope-expr]]
    [dromon.elm.compiler.protocols :refer [-eval]]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [are deftest is]]))


(defn fixture [f]
  (st/instrument)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(deftest attr-test
  (are [elm kw] (= kw (attr elm))
    {:resultTypeName "{http://hl7.org/fhir}Specimen.Collection"
     :path "collection"
     :scope "S"
     :type "Property"
     :life/scopes #{"S"}
     :life/source-type "{http://hl7.org/fhir}Specimen"}
    :Specimen/collection

    {:path "collected"
     :type "Property"
     :resultTypeSpecifier
     {:type "ChoiceTypeSpecifier"
      :choice [{:name "{http://hl7.org/fhir}dateTime"
                :type "NamedTypeSpecifier"}
               {:name "{http://hl7.org/fhir}Period"
                :type "NamedTypeSpecifier"}]}
     :source
     {:resultTypeName "{http://hl7.org/fhir}Specimen.Collection"
      :path "collection"
      :scope "S"
      :type "Property"
      :life/scopes #{"S"}
      :life/source-type "{http://hl7.org/fhir}Specimen"}}
    :Specimen.collection/collected

    {:resultTypeName "{http://hl7.org/fhir}decimal"
     :path "value"
     :type "Property"
     :source
     {:resultTypeName "{http://hl7.org/fhir}Quantity"
      :path "foo"
      :type "Property"}}
    datomic-quantity/value

    {:resultTypeName "{http://hl7.org/fhir}decimal"
     :path "unit"
     :type "Property"
     :source
     {:resultTypeName "{http://hl7.org/fhir}Quantity"
      :path "foo"
      :type "Property"}}
    datomic-quantity/unit

    {:resultTypeName "{http://hl7.org/fhir}decimal"
     :path "system"
     :type "Property"
     :source
     {:resultTypeName "{http://hl7.org/fhir}Quantity"
      :path "foo"
      :type "Property"}}
    datomic-quantity/system

    {:resultTypeName "{http://hl7.org/fhir}decimal"
     :path "code"
     :type "Property"
     :source
     {:resultTypeName "{http://hl7.org/fhir}Quantity"
      :path "foo"
      :type "Property"}}
    datomic-quantity/code))


(deftest scope-expr-test
  (is
    (=
      (-eval
        (scope-expr "C" :CodeableConcept/coding)
        nil
        nil
        {"C" {:CodeableConcept/coding "foo"}})
      "foo")))
