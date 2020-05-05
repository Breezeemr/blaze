(ns dromon.interaction.spec
  (:require
    [dromon.spec]
    [clojure.spec.alpha :as s]))


(s/def :fhir.router.match/data
  (s/keys :req [:dromon/base-url :dromon/context-path]))


(s/def :fhir.router/match
  (s/keys :req-un [:fhir.router.match/data]))
