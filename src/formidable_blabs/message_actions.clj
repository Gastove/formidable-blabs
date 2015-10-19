(ns formidable-blabs.message-actions
  (:require [clj-time.core :as time]
            [clojure
             [edn :as edn]
             [string :as str]]
            [clojure.core
             [async :as async :refer [>! go]]
             [match :as match :refer [match]]
             [strint :refer [<<]]]
            [clojure.core.match.regex :refer :all]
            [clojure.java.io :as io]
            [formidable-blabs
             [channels :refer [outbound-channel]]
             [db :as db]
             [slack :as slack]]
            [taoensso.timbre :as log]))

;; ### Message Actions
;; Actions based on either the sender of a message, the channel of a message,
;; text in a message, or all three. Divides broadly into commands, emotes, and
;; reactions.
;;
;; #### Commands:
;; Explicit orders given to the bot; usually involving calls to either the
;; database or the Slack API.
;; !define -- Term definitions
;; !whatis -- Term lookup
;; !quote - Quote storage and search
;; !impersonate (not yet implemented) -- Remix somebody's words
;;
;; #### Emotes:
;; Intentionally triggered actions that always return a random reaction.
;; !wat
;; !welp
;; !nope
;; !tableflip
;; !darkglasses (not implemented)
;;
;; ### Reactions:
;; Things the bot does on its own based on text triggers. Usually either
;; rate-limited, probabalistic, or both.
;; business (not implemented)
;; Hello / goodbye (not implemented)
;; [wh]oops
;; Random emotes

(defn send-msg-on-channel!
  [slack-channel text]
  (go (>! outbound-channel {:type "message" :channel slack-channel :text text})))

(defn random-emote-by-key
  "Loads a set of responses by key out of resources/emotes.edn; returns a random
  emote"
  [k message emotes]
  (let [flips (k emotes)
        flip (rand-nth flips)
        to-chan (:channel message)]
    (send-msg-on-channel! to-chan flip)))

(declare remove-emoji-and-write! load-emoji-on-file)
(defn random-emoji
  [message emojis]
  (let [emoji (rand-nth emojis)
        to-chan (:channel message)
        ts (:ts message)
        resp (slack/add-emoji-response emoji to-chan ts)]
    (if (and (= false (:ok resp))
             (= (:error resp) "invalid_name"))
      (do (log/info (<< "Slack responded '~{resp}' for emoji named '~{emoji}, removing it"))
          (remove-emoji-and-write! emoji (load-emoji-on-file))))))

(defn purge-emoji
  "This thing isn't wired up, and shouldn't be, typically. It tries to solve the
  problem, 'how on earth do you get a list of only the emoji names Slack
  actually recognizes?' It does this by taking an immense list of emoji, then
  commenting on a thing in slack until slack says it has to stop, waiting a
  moment, then triggering itself to keep emojiing. #yolo"
  [message]
  (let [to-chan (:channel message)
        ts (:ts message)]
    (loop [emojis (:names (edn/read-string (slurp (io/resource "emoji_names.edn"))))]
      (let [emoji (rand-nth emojis)
            resp (slack/add-emoji-response emoji to-chan ts)]
        (if (and (= false (:ok resp))
                 (= (:error resp) "invalid_name"))
          (do
            (log/debug "Purging:" emoji)
            (Thread/sleep 1000)
            (recur (remove-emoji-and-write! emoji emojis)))
          (if (not= (:error resp) "too_many_reactions")
            (do
              (Thread/sleep 1000)
              (recur emojis))))))
    (log/debug "Finished.")
    (Thread/sleep 5000)
    (slack/post-message (:channel message) "Restarting myself!")))

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
        (log/debug "Rolled an" (str "'" n "'") " probability is:" probability ", passing")))))

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
                    (partial random-emote-by-key :omg)
                    (get-rate-limit :omg)))
(def oops-responder (make-throttled-responder
                     (partial random-emote-by-key :oops)
                     (get-rate-limit :oops)))
(def bam-responder (make-throttled-responder
                     (partial random-emote-by-key :bam)
                     0))
(def random-emoji-responder (make-probabalistic-responder
                             random-emoji
                             (get-probability :random-emoji)))

;; ### Quotes
;; Add a quote, search a quote by term, search a quote and return a specific result
(defn add-quote!
  [{:keys [text channel]}]
  (if-let [[_ user quote-text] (re-find #"(?s)!q[uote]* add (\w+) (.+)" text)]
    (do
      (db/record-quote user quote-text)
      (send-msg-on-channel! channel "Quote added!"))
    (do
      (send-msg-on-channel! channel "Erk! Something didn't work. One thousand apologies.")
      (log/error "Either user or text not found?! That's effed up:" text))))

(defn bounded-rand-int
  [lower upper]
  (loop [n (rand-int upper)]
    (if (< n lower)
      (recur (rand-int upper))
      n)))

(defn extract-quote-num
  [text num-quotes]
  (Integer/parseInt (or (second (re-find #"!q[uote]* \w+ (\d+)" text))
                        (str (bounded-rand-int 1 num-quotes)))))

(defn find-quote
  [{:keys [text channel]}]
  (if-let [[_ user-or-term] (re-find #"!q[uote]* (\w+)" text)]
    (do (log/debug (re-find #"!q[uote]* \w+ (\d+)" text))
        (let [result-seq (db/find-quote-by-user-or-term user-or-term)
              num-quotes (count result-seq)
              n (extract-quote-num text num-quotes)
              ;; Vectors are zero-indexed, so nth accordingly.
              q (nth result-seq (- n 1) (last result-seq))
              {user :user quote-text :quote} q
              msg (<< "~{user}: ~{quote-text} (~{n}/~{num-quotes})")]
          (send-msg-on-channel! channel msg)))))

;; ### Definitions
(defn add-definition!
  [{:keys [text channel]}]
  (if-let [[_ term definition] (re-find #"(?s)!define (\w+): (.+)" text)]
    (do
      (db/record-definition term definition)
      (send-msg-on-channel! channel (<< "Okay! `~{term}` is now defined as, `~{definition}`")))
    (do
      (send-msg-on-channel! "Erk! Something went wrong. I couldn't define that.")
      (log/error "Couldn't get a definition out of:" text))))

(defn send-define-help
  [{:keys [text channel]}]
  (send-msg-on-channel!
   channel
   "I didn't get that. To define a term, use the command format, `!define term: definition`"))

(defn extract-definition-number
  [text num-defs]
  (Integer/parseInt (or (second (re-find #"(?s)!whatis .+ (\d+)" text))
                        (str num-defs))))

(defn third
  [coll]
  (nth coll 2))

(defn find-definition
  [{:keys [text channel]}]
  (let [m (re-find #"(?s)!whatis (.+)\s\d+|!whatis (.+)" text)
        term (or (second m) (third m))
        result-seq (db/find-definiton-by-term term)
        num-defs (count result-seq)
        n (extract-definition-number text num-defs)
        d (nth result-seq (- n 1) (last result-seq))
        {defd-on :defined-at definition :definition} d
        msg (<< "~{term}:\n> ~{definition}\n Definition ~{n} of ~{num-defs}; last defined ~{defd-on}")]
    (send-msg-on-channel! channel msg)))

(defn name-regex [names]
  (if (or (= names :all) (nil? names))
    #"(?s).+"
    (re-pattern (str/join \| names))))

;; ### Dispatcher
;; Matches on the combination of [username text], typically using simple string
;; matching for username and a regexp for text.
;; **Remember:** Matching is done by `re-matches', which only matches if the _entire
;; string_ matches.
(defn message-dispatch
  "Uses regex matching to take a specified action on text."
  [{:keys [user text] :as message :or {text "" user ""}}]
  (let [emotes (load-emotes)
        opt-ins (:opt-ins emotes)
        oops-users (name-regex (:oops opt-ins))
        random-emoji-users (name-regex (:random-emoji opt-ins))
        emoji (load-all-emoji)
        username (slack/get-user-name user)]
    (match [username text]
           [_ #"!wat\s*"] (random-emote-by-key :wat message emotes)
           [_ #"!unicorns\s*"] (random-emote-by-key :unicorns message emotes)
           [_ #"!welp\s*"] (random-emote-by-key :welp message emotes)
           [_ #"!nope\s*"] (random-emote-by-key :nope message emotes)
           [_ #"!tableflip\s*"] (random-emote-by-key :tableflip message emotes)
           [_ #"(?i)[z?omf?g ]+\s*"] (omg-responder message emotes)
           [_ #"(?i)[wh]*oops|uh-oh"] (oops-responder message emotes)
           [_ #"(?i)!?bam!?"] (bam-responder message emotes)
           [_ #"(?s)!q[uote]* add \w+ .+"] (add-quote! message)
           [_ #"!q[uote]* \w+\s?\d*"] (find-quote message)
           [_ #"(?s)!define \w+: .+"] (add-definition! message)
           [_ #"(?s)!define.+"] (send-define-help message)
           [_ #"(?s)!whatis .+"] (find-definition message)
           [random-emoji-users _] (random-emoji-responder message emoji)
           :else (log/debug "No message action found."))))
