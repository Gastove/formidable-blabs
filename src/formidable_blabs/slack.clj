(ns formidable-blabs.slack
  (:require [aleph.http :as aleph]
            [cheshire.core :as json]
            [clojure.core.async :as async :refer [>! go]]
            [formidable-blabs.channels :refer [outbound-channel]]
            [formidable-blabs.config :refer [blabs-config]]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import java.lang.RuntimeException))

(def self (atom ""))

(defn make-slack-request
  ([endpoint] (make-slack-request endpoint {}))
  ([endpoint params]
   (let [cfg (:slack (blabs-config))
         url (str (:api-url cfg) (get-in cfg [:resources endpoint]))
         token (:api-token (:slack (blabs-config)))
         updated-params (assoc params :token token)]
     @(http/get url {:query-params updated-params}))))

(defn make-slack-request-and-parse-body
  ([endpoint] (make-slack-request-and-parse-body endpoint {}))
  ([endpoint params]
   (let [resp (make-slack-request endpoint params)]
     (json/parse-string (:body resp) keyword))))

(defn get-ws-url []
  (let [resp (make-slack-request-and-parse-body :rtm-start)]
    (if (:ok resp)
      resp
      (do
        (log/error "Couldn't connect to slack! Got error: " (:error resp))
        (throw (RuntimeException. "Couldn't open Slack websocket!"))))))

(defn connect-to-slack []
  (let [body (get-ws-url)
        self-map (:self body)
        ws-url (:url body)
        socket-stream @(aleph/websocket-client ws-url)
        chan (async/chan 10 (map #(json/parse-string % keyword)))]
    ;; Store info about the current bot user
    (swap! self (fn [x] self-map))

    ;; Return the socket
    socket-stream))

(defn add-emoji-response
  [emoji to-chan ts]
  (let [params {:channel to-chan
                :timestamp ts
                :name emoji}]
    (make-slack-request-and-parse-body :reactions-add params)))

;; Sending Messages
(defn send-msg-on-channel!
  "Put a message on the global outbound channel for processing via the
  WebSocket."
  [slack-channel text]
  (go (>! outbound-channel {:type "message" :channel slack-channel :text text})))

(defn post-message
  "Posts a message over the https Slack web API."
  [to-chan txt]
  (let [bot-name (get-in (blabs-config) [:bot :bot-name])
        params {:channel to-chan
                :text txt
                :username bot-name}
        response (make-slack-request-and-parse-body :post-message params)]
    (log/debug response)
    response))

(defn open-direct-message-channel
  [user-id]
  (make-slack-request-and-parse-body :open-dm {:user user-id}))

(defn close-direct-message-channel
  [channel]
  (make-slack-request-and-parse-body :close-dm {:channel channel}))

;; User actions
(defn get-info-for-user
  [user-id]
  (make-slack-request-and-parse-body :user-info {:user user-id}))

(defn fetch-user-name
  [user-id]
  (let [body (get-info-for-user user-id)]
    (if (:ok body)
      (get-in body [:user :name])
      (log/error body))))

(def get-user-name (memoize fetch-user-name))

(defn get-info-for-user-name
  [user-name]
  (let [{member-info :members} (make-slack-request-and-parse-body :user-list)]
    (first (filter (fn [m] (= user-name (:name m))) member-info))))

(defn get-user-id-for-user-name
  [user-name]
  (let [user-info (get-info-for-user-name user-name)]
    (:id user-info)))

(defn get-custom-emoji
  []
  (let [resp (make-slack-request-and-parse-body :emoji)]
    (map name (keys (:emoji resp)))))
