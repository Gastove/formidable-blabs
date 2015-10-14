(ns formidable-blabs.message-actions
  (:require [clojure.core.match :as match :refer [match]]
            [clojure.core.match.regex :refer :all]))

;; Remember: Matching is done by `re-matches', which only matches if the _entire
;; string_ matches.
(defn message-dispatch
  [{:keys [text] :as message}]
  (match text
         #"cat\w+" :hi
         #"^cat" :enforced
         :else :nope))
