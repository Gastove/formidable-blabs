(ns formidable-blabs.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [go-loop]]
            [formidable-blabs
             [channels :refer [outbound-channel]]
             [config :refer [blabs-config]]
             [message-actions :refer [message-dispatch]]
             [slack :refer [connect-to-slack make-message-sender]]]
            [manifold.stream :as m]
            [taoensso.timbre :as log]))

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

(defn consume-and-dispatch [socket]
  (go-loop []
    (try (if-let [raw-body @(m/take! socket)]
           (let [body (json/parse-string raw-body keyword)]
             (dispatch body)
             (recur))
           (log/debug "take! from websocket failed, exiting."))
         (catch Exception e e))))

(defn process-outbound
  "Sends outgoing messages. Sends a ping every <config> seconds in which there
  hasn't been any other traffic."
  [out-ch sock]
  (let [ping-millis (* (:ping (blabs-config)) 1000)
        send! (make-message-sender socket)
        send-ping! #(send! {:type "ping"})]
    (go-loop []
      (let [timeout-ch (async/timeout ping-millis)
            [msg from-ch] (async/alts! [out-ch timeout-ch])]
        (cond
          (= from-ch out-ch) (send! msg)
          (= from-ch timeout-ch) (send-ping!)))
      (recur))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [socket (connect-to-slack)]
    (consume-and-dispatch ch)
    (process-outbound outbound-channel sock)))
