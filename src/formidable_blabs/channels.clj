(ns formidable-blabs.channels
  "Utility NS; holds the channel other namespaces will put their outgoing
  actions on. Isolated to it's own NS to avoid circular dependencies and ease
  importing."
  (:require [clojure.core.async :refer [chan]]))

(def outbound-channel (chan 50))
