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

(declare remove-emoji-and-write! load-emoji-on-file)
(defn random-emoji
  [message emojis]
  (let [emoji (rand-nth emojis)
        to-chan (:channel message)
        ts (:ts message)
        resp (slack/add-emoji-response emoji to-chan ts)]
    (if (and (= false (:ok resp))
             (= (:error resp) "invalid_name"))
      (do (log/info "Slack responded:" resp "for emoji named:" emoji ", removing it")
          (remove-emoji-and-write! emoji (load-emoji-on-file))))))

(defn remove-emoji-and-write!
  [emoji emojis]
  (let [new-emojis (vec (remove #{emoji} emojis))]
    (spit (io/resource "emoji_names.edn") (with-out-str (pr {:names new-emojis})))
    new-emojis))

;; ### Random Actions
;; Given a percent chance in 100 an action should occur, conditionally do the
;; action or pass
(defn make-probabalistic-responder
  [action probability]
  (fn [& args]
    (let [n (rand-int 99)]
      (if (< n probability)
        (apply action args)
        (log/debug "Rolled an '" n "', probability is " probability ", passing")))))

;; ### Rate Limits
;; Some things shouldn't run all the time. This wrapper makes a function get
;; called no more than every throttle-seconds seconds.
(defn make-throttled-responder
  "Not every response should happen every time."
  [action throttle-seconds]
  (let [last-replied (atom (time/date-time 0))]
    (fn [& action-args]
      (let [since-last-millis (* throttle-seconds 1000)]
        (if (time/after? (time/now) (time/plus @last-replied (time/millis since-last-millis)))
          (do (apply action action-args)
              (swap! last-replied (fn [x] (time/now))))
          (log/info "Not performing action yet, too soon"))))))

;; ### Check to see if it's time to do an action; if so, check its probability.
(defn make-probabalistic-throttled-responder
  ""
  [action probability throttle-seconds]
  (make-probabalistic-responder
   (make-throttled-responder action probability) throttle-seconds))

(defn load-emotes []
  (edn/read-string (slurp (io/resource "emotes.edn") :encoding "utf-16")))

(defn load-emoji-on-file
  []
  (:names (edn/read-string (slurp (io/resource "emoji_names.edn")))))

(defn load-all-emoji []
  (let [emojis-on-file (load-emoji-on-file)
        custom-emoji (slack/get-custom-emoji)]
    (into emojis-on-file custom-emoji)))

(defn get-rate-limit [k]
  (let [emotes (load-emotes)
        rates (:rate-limits emotes)]
    (get rates k 10)))

(defn get-probability [k]
  (let [emotes (load-emotes)
        probabilities (:probabilities emotes)]
    (get probabilities k 50)))

(def omg-responder (make-throttled-responder
                    (partial random-emote-by-key :omg) (get-rate-limit :omg)))
(def oops-responder (make-throttled-responder
                     (partial random-emote-by-key :oops) (get-rate-limit :oops)))

;; ### Dispatcher
;; **Remember:** Matching is done by `re-matches', which only matches if the _entire
;; string_ matches.
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
           [_ #"(?i)[omf?g ]+\s*"] (omg-responder message emotes)
           [_ #"(?i)[wh]?oops|uh-oh"] (oops-responder message emotes)
           :else (log/debug "No message action found."))))
