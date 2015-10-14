(ns formidable-blabs.core
  (:require [aleph.http :as aleph]
            [chord.http-kit :as http-kit]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [formidable-blabs.config :refer [blabs-config]]
            [manifold.stream :as m]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

(defn get-ws-url []
  (let [url (:slack-start-url (blabs-config))
        token (:slack-api-token (blabs-config))]
    (http/get url {:query-params {:token token}})))

(defn connect-to-slack []
  (let [resp (get-ws-url)
        body (json/read-str (:body @resp) :key-fn keyword)
        ws-url (:url body)
        socket-stream @(aleph/websocket-client ws-url)
        chan (async/chan 10 (map #(json/read-str % :keyword keyword)))]
    (m/connect socket-stream chan)
    chan))

(defmulti dispatch #(:type %))
(defmethod dispatch "hello" [event]
  (log/info "Got hello!"))
(defmethod dispatch "message" [event])

(defn consume-and-dispatch [in-ch]
  (async/go-loop [body (async/<! in-ch)]
    (dispatch body)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
