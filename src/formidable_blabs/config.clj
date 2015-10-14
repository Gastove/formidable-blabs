(ns formidable-blabs.config
  (:require [clojure.java.io :as io]
            [nomad :as nomad]))

(nomad/defconfig blabs-config (io/resource "config.edn"))
