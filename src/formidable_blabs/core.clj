(ns formidable-blabs.core
  "This is the main orchestration namespace. It provides a main method
  and a `dispatch` multimethod for taking action on events from Slack,
  as well as methods to consume from the Slack RTM API and pass
  messages back in to it."
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.core.async :as async :refer [go-loop]]
            [clojure.core.strint :refer [<<]]
            [formidable-blabs
             [channels :refer [outbound-channel]]
             [config :refer [blabs-config]]
             [message-actions :refer [message-dispatch]]
             [slack :refer [connect-to-slack]]]
            [manifold.stream :as m]
            [taoensso.timbre :as log]))

;; ### Event Dispatch
;; Dispatches on the `:type` key of a parsed JSON body from Slack's RTM API.
;; See See https://api.slack.com/rtm for a full list of possible events, and
;; more about the RTM API itself.

(defmulti dispatch #(:type %))
(defmethod dispatch "hello" [_] (log/info "Got hello!"))
(defmethod dispatch "pong" [_] (log/debug "Got pong."))
(defmethod dispatch "message" [event] (message-dispatch event))
(defmethod dispatch :default [event]
  ;; This logs a *lot* of stuff -- like keep-alives. Useful for
  ;; I'm-debugging-right-now debugging and nothing else.
  ;; (log/debug "No :type key in event or event type unrecognized:" event)
  )

(defn make-message-maker []
  "Makes a function which assocs a monotomically increasing integer in to a
  message body as the `:id` of that message. Slack requires this id be a)
  present and b) unique to a given _connection_ -- but it can be reset each
  connection."
  (let [id-cache (atom 0)]
    (fn [msg]
      (let [id (swap! id-cache inc)
            msg-body (assoc msg :id id)]
        (json/generate-string msg-body)))))

(defn parse-and-dispatch
  "Parse a JSON string into a Clojure map and call dispatch on it."
  [raw-body]
  (let [body (json/parse-string raw-body keyword)]
    (dispatch body)))

(defn consume-and-dispatch
  "Consume off a websocket and dispatch, then loop.

  Throws `IOException` if it can't read off the socket."
  [socket]
  (go-loop []
    (if-let [raw-body @(m/take! socket)]
      (do (parse-and-dispatch raw-body)
          (recur))
      (throw (java.io.IOException. "take! from websocket failed")))))

(defn process-outbound
  "Sends outgoing messages. Sends a ping every `config` seconds in which there
  hasn't been any other traffic."
  [out-ch socket]
  (let [ping-millis (* (:ping (blabs-config)) 1000)
        ping {:type "ping"}
        make-message (make-message-maker)]
    (go-loop []
      (let [timeout-ch (async/timeout ping-millis)
            [maybe-msg from-ch] (async/alts!! [out-ch timeout-ch])
            msg-body (if (= from-ch out-ch) maybe-msg (do (log/debug "Sending ping") ping))
            msg (make-message msg-body)]
        (if @(m/put! socket msg)
          (recur)
          (throw (java.io.IOException. "put! failed on socket")))))))

(defn next-backoff
  "Doubles backoff, up to 5 minutes."
  [last-backoff]
  (let [five-minutes (* 60 1000 5)]
    (if (< last-backoff five-minutes)
      (* 2 last-backoff)
      five-minutes)))

(defn -main
  "Runs the whole works. Runs two asynchronous processes: one to consume from
  Slack and dispatch events; one to consume from an internal channel and send
  events to Slack. The only feedback these procs will return is to throw an
  error, which the main thread will catch, log, and then re-connect after
  sleeping `backoff` seconds."
  [& args]
  (loop [backoff 1000]
    (try
      (let [socket (connect-to-slack)]
        ;; Receive an error thrown by either process.
        (async/alts!! [(consume-and-dispatch socket)
                       (process-outbound outbound-channel socket)]))
      ;; A `NullPointerException` occurs when we can't connect to Slack
      (catch java.lang.NullPointerException npe
        (log/error (<< "Couldn't re-open websocket, retrying with ~(/ backoff 1000) milliseconds of backoff")))
      ;; `IOException` is thrown when we can't read from a websocket
      (catch java.io.IOException ioe
        (log/error (<< "Caught IOException; couldn't read from websocket. Backing off ~(/ backoff 1000) seconds, then reconnecting")))
      ;; In case of other unforseen errors.
      (catch Exception e (log/error e)))
    (Thread/sleep backoff)
    (recur (next-backoff backoff))))
