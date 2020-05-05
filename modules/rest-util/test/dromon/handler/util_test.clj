(ns dromon.handler.util-test
  (:require
    [dromon.handler.util :refer [preference]]
    [clojure.test :refer [deftest is]]
    [clojure.spec.test.alpha :as st]))


(st/instrument)


(deftest preference-test
  (is (= "representation"
         (preference {"prefer" "return=representation"} "return"))))
