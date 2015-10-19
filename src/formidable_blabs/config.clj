(ns formidable-blabs.config
  (:require [clojure.java.io :as io]
            [taoensso.timbre :refer [set-config! set-level!]]
            [taoensso.timbre.appenders.core :as appenders]
            [nomad :as nomad]))

(nomad/defconfig blabs-config (io/resource "config.edn"))


(set-config! {:level (get-in (blabs-config) [:logging :level])
              :appenders {:spit (appenders/spit-appender (get-in (blabs-config) [:logging :spit]))
                          :print (appenders/println-appender (get-in (blabs-config) [:logging :spit]))}})
