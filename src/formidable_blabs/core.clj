(ns formidable-blabs.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [go-loop]]
            [formidable-blabs
             [channels :refer [outbound-channel]]
             [config :refer [blabs-config]]
             [message-actions :refer [message-dispatch]]
             [slack :refer [connect-to-slack make-message-sender]]]
            [taoensso.timbre :as log]))

(defmulti dispatch #(:type %))
(defmethod dispatch "hello" [_] (log/info "Got hello!"))
;; (defmethod dispatch "pong" [_] (log/debug "Got pong."))
(defmethod dispatch "message" [event] (message-dispatch event))
(defmethod dispatch :default [event]
  ;; This logs a *lot* of stuff -- like keep-alives. Useful for
  ;; I'm-debugging-right-now debugging and nothing else.
  ;; (log/debug "No :type key in event or event type unrecognized:" event)
  )

(defn consume-and-dispatch [in-ch]
  (go-loop [body (async/<! in-ch)]
    (dispatch body)
    (if-let [next-item (async/<! in-ch)]
      (recur next-item)
      (log/debug "Got nil on in-ch, exiting."))))

(defn process-outbound
  "Sends outgoing messages. Sends a ping every <config> seconds in which there
  hasn't been any other traffic."
  [out-ch sock]
  (let [ping-millis (* (:ping (blabs-config)) 1000)
        send! (make-message-sender sock)]
    (loop []
      (let [timeout-ch (async/timeout ping-millis)
            [msg from-ch] (async/alts!! [out-ch timeout-ch])
            ping {:type "ping"}]
        (cond
          (= from-ch out-ch) (send! msg)
          (= from-ch timeout-ch) (send! ping)))
      (recur))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [[sock ch] (connect-to-slack)]
    (consume-and-dispatch ch)
    (process-outbound outbound-channel sock)))
