(defproject blaze "0.6.4"
  :description "A FHIR Store with internal, fast CQL Evaluation Engine"
  :url "https://github.com/life-research/blaze"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.0.0"
  :pedantic? :abort

  ;; Added by Ben
  :repositories [["my.datomic.com"
                  {:url      "https://my.datomic.com/repo"
                   :username "matwi@pgacadiana.com"
                   :password "a1103d51-5bf9-47c0-8aa7-28668f0b63d3"}]
                 ["snapshots" {:url "s3p://breezepackages/snapshots" :creds :gpg}]
                 ["releases" {:url "s3p://breezepackages/releases" :creds :gpg}]]
  ;; Added by Ben
  :managed-dependencies [[com.google.guava/guava "27.1-jre"]
                         ;; Logging
                         [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                         [org.slf4j/jul-to-slf4j "1.7.25"]
                         [org.slf4j/jcl-over-slf4j "1.7.25"]
                         [org.slf4j/log4j-over-slf4j "1.7.25"]
                         ]

  :dependencies
  [[aleph "0.4.7-alpha1"]
   [camel-snake-kebab "0.4.0"]
   [cheshire "5.9.0"]
   [com.cognitect/anomalies "0.1.12"]
   #_[com.datomic/datomic-free "0.9.5697"]
   [com.taoensso/timbre "4.10.0"]
   [info.cqframework/cql-to-elm "1.4.6"]
   [integrant "0.7.0"]
   [io.prometheus/simpleclient_hotspot "0.6.0"]
   [javax.measure/unit-api "1.0"]
   [metosin/reitit-ring "0.3.9"
    :exclusions [commons-codec]]
   [org.apache.httpcomponents/httpcore "4.4.11"]
   [org.clojars.akiel/datomic-spec "0.5.2"]
   [org.clojars.akiel/datomic-tools "0.4"]
   [org.clojars.akiel/env-tools "0.2.1"]
   [org.clojars.akiel/spec-coerce "0.3.1"]
   [org.clojure/clojure "1.10.1"]
   [org.clojure/core.cache "0.8.1"]
   [org.clojure/tools.reader "1.3.2"]
   [phrase "0.3-alpha3"]
   [prom-metrics "0.5-alpha2"]
   [ring/ring-core "1.7.1"
    :exclusions [clj-time commons-codec commons-fileupload
                 commons-io crypto-equality crypto-random]]
   [systems.uom/systems-ucum "0.9"]
   [systems.uom/systems-quantity "1.0"]

   ;; Needed to work with Java 11. Doesn't hurt Java 8.
   [javax.xml.bind/jaxb-api "2.4.0-b180830.0359"]
   [com.sun.xml.bind/jaxb-core "2.3.0.1"]
   [com.sun.xml.bind/jaxb-impl "2.3.2"]

   ;; Added by Ben
   [mysql/mysql-connector-java "5.1.47"]
   [com.datomic/datomic-pro "0.9.5951"
    :exclusions [org.slf4j/slf4j-nop org.slf4j/slf4j-log4j12]]
   ]

  :plugins [[lein-cloverage/lein-cloverage "1.1.1"]]

  :profiles
  {:dev
   {:source-paths ["dev"]
    :dependencies
    [[criterium "0.4.5"]
     [org.clojars.akiel/iota "0.1"]
     [org.clojure/data.xml "0.0.8"]
     [org.clojure/test.check "0.10.0"]
     [org.clojure/tools.namespace "0.3.1"]]}

   :uberjar
   {:aot [blaze.core]}}

  :main ^:skip-aot blaze.core

  :hiera {:ignore-ns #{user}})
