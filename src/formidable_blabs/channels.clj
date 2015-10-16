(ns formidable-blabs.channels
  (:require [clojure.core.async :refer [chan]]))

(def outbound-channel (chan 50))
