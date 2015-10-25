(ns formidable-blabs.config
  (:require [clojure.java.io :as io]
            [taoensso.timbre :refer [set-config! set-level!]]
            [taoensso.timbre.appenders.core :as appenders]
            [nomad :as nomad]))

;; Load configs.
(nomad/defconfig blabs-config (io/resource "config.edn"))

;; Logging configs
(set-config!
 ;; Set log global log level from config file based on env.4
 {:level (get-in (blabs-config) [:logging :level])
  ;; Configure stdout (spit) and file (print) loggers from config file
  :appenders {:spit (appenders/spit-appender (get-in (blabs-config) [:logging :spit]))
              :print (appenders/println-appender (get-in (blabs-config) [:logging :print]))}})
