(ns formidable-blabs.slack
  (:require [formidable-blabs.config :refer [blabs-config]]
            [org.httpkit.client :as http]
            [aleph.http :as aleph]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [manifold.stream :as m]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [manifold.stream :as m]
            [taoensso.timbre :as log]
            [clojure.core.async :as async]
            [manifold.stream :as m]
            [taoensso.timbre :as log]))

(defn get-ws-url []
  (let [cfg (:slack (blabs-config))
        url (str (:api-url cfg) (get-in cfg [:resources :rtm-start]))
        token (:api-token (:slack (blabs-config)))]
    (http/get url {:query-params {:token token}})))

(defn connect-to-slack []
  (let [resp (get-ws-url)
        body (json/parse-string (:body @resp) keyword)
        ws-url (:url body)
        socket-stream @(aleph/websocket-client ws-url)
        chan (async/chan 10 (map #(json/parse-string % keyword)))]
    (m/connect socket-stream chan)
    [socket-stream chan]))

(defn send-message!
  [msg conn]
  (let [body-json (json/generate-string msg)]
    (log/debug "Sending:" msg)
    (m/put! conn body-json)))

(defn make-message-sender [conn]
  (let [id-cache (atom 0)]
    (fn [msg]
      (let [id (swap! id-cache inc)
            msg-with-id (assoc msg :id id)]
        (send-message! msg-with-id conn)))))
