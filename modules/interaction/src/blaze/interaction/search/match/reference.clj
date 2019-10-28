;; By pointing at a ns other then this one you can provide
;; alternative search params resolvers.

(ns blaze.interaction.search.match.reference
  (:require
   [integrant.core :as ig]))

(defn match-reference?
  [search reference]
  (= (:Patient/id reference)
     search))

(defmethod ig/init-key :blaze.interaction.search.match/reference
  [_ _]
  match-reference?)
