(ns formidable-blabs.message-actions
  (:require [clj-time.core :as time]
            [clojure.core.async :as async :refer [go >!]]
            [clojure.core.match :as match :refer [match]]
            [clojure.core.match.regex :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [formidable-blabs.channels :refer [outbound-channel]]
            [taoensso.timbre :as log]))

;; ### Message Actions
;; Actions based on either the sender of a message, the channel of a message,
;; text in a message, or all three. Divides broadly into commands, emotes, and
;; reactions.
;;
;; #### Commands:
;; !define
;; !whatis
;; !quote
;; !impersonate
;;
;; #### Emotes:
;; !wat
;; !welp
;; !nope
;; !tableflip
;;
;; ### Reactions:
;; business
;; !darkglasses
;; Hello / goodbye

(defn random-emote-by-key
  "Loads a set of responses by key out of resources/emotes.edn; returns a random
  emote"
  [k message emotes]
  (let [flips (k emotes)
        flip (rand-nth flips)
        to-chan (:channel message)
        out-msg {:type "message"
                 :text flip
                 :channel to-chan}]
    (go (>! outbound-channel out-msg))))

(defn make-throttled-responder
  "Not every response should happen every time."
  [action throttle-seconds]
  (let [last-replied (atom (time/date-time 0))]
    (fn [& action-args]
      (let [since-last-millis (* throttle-seconds 1000)]
        (if (time/after? (time/now) (time/plus @last-replied (time/millis since-last-millis)))
          (do (apply action action-args)
              (swap! last-replied (fn [x] (time/now)))
              (log/debug "It's time! Actioning"))
          (log/info "Not performing action yet, too soon"))))))

(def omg-responder (make-throttled-responder (partial random-emote-by-key :omg) 10))

;; ### Dispatcher
;; **Remember:** Matching is done by `re-matches', which only matches if the _entire
;; string_ matches.

(defn load-emotes []
  (edn/read-string (slurp (io/resource "emotes.edn") :encoding "utf-16")))

(defn message-dispatch
  ""
  [{:keys [user text] :as message :or {text "" user ""}}]
  (log/debug message)
  (let [emotes (load-emotes)]
    (match [user text]
           [_ #"(?s)!define.+"] (log/debug "'!wat' command not yet implemented")
           [_ #"(?s)!whatis.+"] (log/debug "'!whatis' command not yet implemented")
           [_ #"(?s)!quote.+"] (log/debug "'!quote' command not yet implemented")
           [_ #"!wat\s*"] (random-emote-by-key :wat message emotes)
           [_ #"!welp\s*"] (random-emote-by-key :welp message emotes)
           [_ #"!nope\s*"] (random-emote-by-key :nope message emotes)
           [_ #"!tableflip\s*"] (random-emote-by-key :tableflip message emotes)
           [_ #"[omf?g ]+\s*"] (omg-responder message emotes)
           :else (log/debug "No message action found."))))
