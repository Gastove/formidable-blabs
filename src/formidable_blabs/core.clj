(ns formidable-blabs.core
  "This is the main orchestration namespace. It provides a main method and a
  `dispatch` multimethod for taking action on events from Slack.
  "
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

;; h/t David Nolen
(defn throw-err [e]
  (when (instance? Throwable e) (throw e))
  e)

(defmacro <? [ch]
  `(throw-err (async/<! ~ch)))

(defmacro ?> [ch item]
  `(throw-err (async/>! ~ch ~item)))

(defmulti dispatch #(:type %))
(defmethod dispatch "hello" [_] (log/info "Got hello!"))
(defmethod dispatch "pong" [_] (log/debug "Got pong."))
(defmethod dispatch "message" [event] (message-dispatch event))
(defmethod dispatch :default [event]
  ;; This logs a *lot* of stuff -- like keep-alives. Useful for
  ;; I'm-debugging-right-now debugging and nothing else.
  ;; (log/debug "No :type key in event or event type unrecognized:" event)
  )

(defn parse-and-dispatch
  [raw-body]
  (let [body (json/parse-string raw-body keyword)]
    (dispatch body)))

(defn consume-and-dispatch
  [socket]
  (go-loop []
    (if-let [raw-body @(m/take! socket)]
      (do (parse-and-dispatch raw-body)
          (recur))
      (throw (java.io.IOException. "take! from websocket failed")))))

(defn make-message-maker []
  (let [id-cache (atom 0)]
    (fn [msg]
      (let [id (swap! id-cache inc)
            msg-body (assoc msg :id id)]
        (json/generate-string msg-body)))))

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
  [last-backoff]
  (let [five-minutes (* 60 1000 5)]
    (if (< last-backoff five-minutes)
      (* 2 last-backoff)
      five-minutes)))

(defn -main
  "Runs the whole works. Starts a background process to consume/dispatch events,
  and a foreground proc to send them to Slack."
  [& args]
  (loop [backoff 1000]
    (try
      (let [socket (connect-to-slack)]
        (throw (async/alts!! [(consume-and-dispatch socket)
                              (process-outbound outbound-channel socket)])))
      (catch java.lang.NullPointerException e
        (log/error (<< "Couldn't re-open websocket, retrying with ~(/ backoff 1000) milliseconds of backoff")))
      (catch Exception e (log/error e)))
    (Thread/sleep backoff)
    (recur (next-backoff backoff))))
