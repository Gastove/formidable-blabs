(defproject formidable-blabs "0.1.0-SNAPSHOT"
  :description "Help I accidentally a Slack bot"
  :url "http://github.com/Gastove/formidable-blabs"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [jarohen/chord "0.6.0"]
                 [jarohen/nomad "0.7.2"]
                 [aleph "0.4.0"]
                 [manifold "0.1.1"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/core.match "0.3.0-alpha4"]]
  :main ^:skip-aot formidable-blabs.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
