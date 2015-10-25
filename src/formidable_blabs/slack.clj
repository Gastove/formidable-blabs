(ns formidable-blabs.slack
  (:require [aleph.http :as aleph]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [formidable-blabs.config :refer [blabs-config]]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

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
  (make-slack-request-and-parse-body :rtm-start))

;; TODO: stop connecting here, just return socket
(defn connect-to-slack []
  (let [body (get-ws-url)
        ws-url (:url body)
        socket-stream @(aleph/websocket-client ws-url)
        chan (async/chan 10 (map #(json/parse-string % keyword)))]
    ;; (m/connect socket-stream chan)
    socket-stream))

(defn add-emoji-response
  [emoji to-chan ts]
  (let [params {:channel to-chan
                :timestamp ts
                :name emoji}]
    (make-slack-request-and-parse-body :reactions-add params)))

(defn post-message
  "Posts a non-standard message over https; mostly for doing nonsense."
  [to-chan txt]
  (let [cfg (:slack (blabs-config))
        url (str (:api-url cfg) "chat.postMessage")
        token (:api-token (:slack (blabs-config)))
        params {:token token
                :channel to-chan
                :text txt}]
    (log/debug @(http/get url {:query-params params}))))

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

(defn get-custom-emoji
  []
  (let [resp (make-slack-request-and-parse-body :emoji)]
    (map name (keys (:emoji resp)))))
