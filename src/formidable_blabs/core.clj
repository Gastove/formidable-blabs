(ns formidable-blabs.core
  (:gen-class)
  (:require [aleph.http :as aleph]
            [chord.http-kit :as http-kit]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [formidable-blabs.channels :refer [outbound-channel]]
            [formidable-blabs.config :refer [blabs-config]]
            [formidable-blabs.message-actions :refer [message-dispatch]]
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
        chan (async/chan 10 (map #(json/read-str % :key-fn keyword)))]
    (m/connect socket-stream chan)
    [socket-stream chan]))

(defn send-message!
  [[to-slack-channel message] message-id conn]
  (let [body-map {:type "message"
                  :text message
                  :id message-id
                  :channel to-slack-channel}
        body-json (json/write-str body-map)]
    (log/debug "Sending:" body-json)
    (m/put! conn body-json)))

(defn make-message-sender []
  (let [id-cache (atom 0)]
    (fn [chan-msg-pair conn]
      (let [id (swap! id-cache inc)]
        (send-message! chan-msg-pair id conn)))))

(defmulti dispatch #(:type %))
(defmethod dispatch "hello" [event]
  (log/info "Got hello!"))
(defmethod dispatch "message" [event] (message-dispatch event))
(defmethod dispatch :default [event]
  ;; This logs a *lot* of stuff -- like keep-alives. Useful for
  ;; I'm-debugging-right-now debugging and nothing else.
  ;; (log/debug "No :type key in event or event type unrecognized:" event)
  )

(defn consume-and-dispatch [in-ch]
  (async/go-loop [body (async/<! in-ch)]
    (dispatch body)
    (if-let [next-item (async/<! in-ch)]
      (recur next-item)
      (log/debug "Got nil on in-ch, exiting."))))

(defn tryit []
  "Opens a connection to slack and starts running the whole works.

  Returns a function that closes up the whole works when called."
  (let [[sock ch] (connect-to-slack)
        send! (make-message-sender)]
    (consume-and-dispatch ch)
    (async/go-loop [outgoing (async/<! outbound-channel)]
      (send! outgoing sock)
      (if-let [next-item (async/<! outbound-channel)]
        (recur next-item)
        (log/debug "Got nil on outbound channel, exiting.")))
    (fn [] (m/close! sock) (async/close! ch) (async/close! outbound-channel))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [[sock ch] (connect-to-slack)
        send! (make-message-sender)]
    (consume-and-dispatch ch)
    (async/go-loop [outgoing (async/<! outbound-channel)]
      (send! outgoing sock)
      (if-let [next-item (async/<! outbound-channel)]
        (recur next-item))))
  (log/info "We're up!"))
