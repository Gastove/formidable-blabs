(defproject formidable-blabs "0.1.0-SNAPSHOT"
  :description "Help I accidentally a Slack bot"
  :url "http://github.com/Gastove/formidable-blabs"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [jarohen/chord "0.6.0]"]
  :main ^:skip-aot formidable-blabs.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
