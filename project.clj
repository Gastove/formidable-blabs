(defproject formidable-blabs "0.1.0-SNAPSHOT"
  :description "Help I accidentally a Slack bot"
  :url "http://github.com/Gastove/formidable-blabs"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [jarohen/nomad "0.7.2"]
                 [aleph "0.4.0"]
                 [manifold "0.1.1"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cheshire "5.5.0"]
                 [http-kit "2.1.19"]
                 [clj-time "0.11.0"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [korma "0.4.2"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [camel-snake-kebab "0.3.2"]
                 [org.clojure/core.incubator "0.1.3"]]
  :plugins [[michaelblume/lein-marginalia "0.9.0"]]
  :aliases {"init-db" ["run" "-m" "formidable-blabs.db/setup-db!"]}
  :main ^:skip-aot formidable-blabs.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
