(ns formidable-blabs.config
  (:require [clojure.java.io :as io]
            [taoensso.timbre :refer [merge-config!]]
            [taoensso.timbre.appenders.core :as appenders]
            [nomad :as nomad]))

(nomad/defconfig blabs-config (io/resource "config.edn"))

(if (= "dev" (:nomad/environment (blabs-config)))
  (do ;; dev configs
    (merge-config! {:appenders {:spit (appenders/spit-appender {:fname "formidable.log"
                                                                :async? true})}}))
  (do ;; production configs
    ))
