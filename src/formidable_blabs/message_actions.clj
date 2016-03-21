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
             [slack :as slack :refer [send-msg-on-channel!]]]
            [formidable-blabs.message-actions.help :as help]
            [taoensso.timbre :as log]))

;; ### Message Actions
;; Actions based on either the sender of a message, the channel of a message,
;; text in a message, or all three. Divides broadly into commands, emotes, and
;; reactions.
;;
;; #### Commands:
;; Explicit orders given to the bot; usually involving calls to either the
;; database or the Slack API.
;;
;; - !define -- Term definitions
;; - !whatis -- Term lookup
;; - !quote - Quote storage and search
;; - !impersonate (not yet implemented) -- Remix somebody's words
;;
;; #### Emotes:
;; Intentionally triggered actions that always return a random reaction.
;;
;; - !wat
;; - !welp
;; - !nope
;; - !tableflip
;; - !darkglasses (not implemented)
;;
;; ### Reactions:
;; Things the bot does on its own based on text triggers. Usually either
;; rate-limited, probabalistic, or both.
;;
;; - business (not implemented)
;; - Hello / goodbye (not implemented)
;; - [wh]oops
;; - Random emotes

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
  "Loads known emoji from file, adds in team custom emoji from the Slack
  API. Selects one at random, adds it as a response to a message. If the emoji
  name isn't recognized, purges it from the known emoji list."
  [message emojis]
  (log/debug "Responding with a random emoji")
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
  "Purge an emoji from the known emoji list and update the emoji file
  on disk."
  [emoji emojis]
  (let [new-emojis (vec (remove #{emoji} emojis))]
    (spit (io/resource "emoji_names.edn")
          (with-out-str (pr {:names new-emojis})))
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


;; ### Responders
;; Here's how we actually assemble the above into something that'll check
;; timeouts and probabilities, then respond with an appropriate random reaction.
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
  (if-let [[_ user quote-text] (re-find #"(?s)!q[uote]* add ([\w\s\.-]+): (.+)" text)]
    (do
      (db/record-quote user quote-text)
      (send-msg-on-channel! channel "Quote added!"))
    (do
      (send-msg-on-channel! channel "Erk! Something didn't work. One thousand apologies.")
      (log/error "Either user or text not found?! That's effed up:" text))))

(defn bounded-rand-int
  [lower upper]
  (cond
    (= lower upper) lower
    (< (- upper lower) 100) (rand-nth (range lower upper))
    :else (loop [n (rand-int upper)]
            (if (< n lower)
              (recur (rand-int upper))
              n))))

(defn extract-num-with-regex
  "Given a text to look in and a regex that captures a number from that text,
  parse that text and return the number as an Integer, or return a sensible
  default."
  ([text num-quotes r] (extract-num-with-regex text num-quotes r identity))
  ([text num-quotes r not-found-fn]
   (if-let [found (re-find r text)]
     (let [parsed-int (Integer/parseInt (second found))]
       (cond
         (< parsed-int 1) 1
         (> parsed-int num-quotes) num-quotes
         :else parsed-int))
     (not-found-fn num-quotes))))

(defn extract-quote-num
  [text num-quotes]
  (extract-num-with-regex text
                          num-quotes
                          #"!q[uote]* \w+ (\d+)"
                          (partial bounded-rand-int 1)))

(defn extract-definition-number
  [text num-defs]
  (extract-num-with-regex text num-defs #"(?s)!whatis .+ (\d+)" identity))

(defn find-quote-for-name
  ([m]
   (find-quote-for-name m
                        send-msg-on-channel!
                        db/find-quote-by-user-or-term))
  ([{:keys [text channel]} send-fn lookup-fn]
   (if-let [[_ user-or-term] (re-find #"!q[uote]* ([\w\s\.-]+)" text)]
     (let [result-seq (lookup-fn user-or-term)]
       (if-not (empty? result-seq)
         (let [num-quotes (count result-seq)
               n (extract-quote-num text num-quotes)
               ;; Vectors are zero-indexed, so nth accordingly.
               {user :user quote-text :quote} (nth result-seq (- n 1))
               msg (<< "~{user}: ~{quote-text} (~{n}/~{num-quotes})")]
           (send-fn channel msg))
         (log/debug (<< "No quote found for ~{user-or-term}")))))))

(defn find-random-quote
  ([m] (find-random-quote m send-msg-on-channel!))
  ([{:keys [channel]} send-fn]
   (let [all-quotes (db/find-all-quotes)]
     (if-not (empty? all-quotes)
       (let [{:keys [user quote]} (rand-nth all-quotes)
             msg (<< "~{user}: ~{quote}")]
         (send-fn channel msg))
       (send-fn channel "Quote DB is empty! Quote some things and try again")))))

;; ### Definitions
(defn add-definition!
  ([m] (add-definition! m send-msg-on-channel!))
  ([{:keys [text channel]} send-fn]
   (if-let [[_ term definition] (re-find #"(?s)!define (\w+): (.+)" text)]
     (do
       (db/record-definition term definition)
       (send-fn channel (<< "Okay! `~{term}` is now defined as, `~{definition}`")))
     (do
       (send-fn "Erk! Something went wrong. I couldn't define that.")
       (log/error "Couldn't get a definition out of:" text)))))

(defn send-define-help
  [{:keys [text channel]}]
  (let [msg (str "I didn't get that. To define a term, use the command"
                 " format, `!define term: definition`")]
    (send-msg-on-channel! channel msg)))

(defn third
  [coll]
  (nth coll 2))

;; ### Definition Lookup
;; You may be thinking, `find-defintion` looks an _awful lot_ like
;; `find-quote-for-name` -- and you're right. The important difference
;; is: you can define nearly anything, so the regex must match on `.+` to be
;; sure of getting everything -- which means, `term` needs to be parsed out with
;; `second`. Haven't figured out _quite_ how to abstract this all together yet.
(defn find-definition
  ([m] (find-definition m send-msg-on-channel! db/find-definiton-by-term))
  ([{:keys [text channel]} send-fn lookup-fn]
   (let [m (re-find #"(?s)!whatis (.+)\s\d+|!whatis (.+)" text)
         term (or (second m) (third m))
         result-seq (lookup-fn term)
         num-defs (count result-seq)
         n (extract-definition-number text num-defs)
         d (nth result-seq (- n 1))
         {defd-on :defined-at definition :definition} d
         msg (<< "~{term}:\n> ~{definition}\n Definition ~{n} of ~{num-defs}; last defined ~{defd-on}")]
     (send-fn channel msg))))

(defn name-regex [names]
  (if (or (= names :all) (nil? names))
    #"(?s).+"
    (re-pattern (str/join \| names))))

;; ### The Action Dispatcher
(defmulti dispatch-action
  " `dispatch-action' dispatches on the `:action' key of a `message-dispatch'
  match map. While `message-dispatch' uses regex matching to see if there's an
  action to take at all, `dispatch-action' answers the question, \"what do we do
  with a given action?\"

  Expects a map:
  {
  :action -- required --  the map key this function dispatches on
  :msg -- required -- the original slack message map being responded to; required by
    most actions to, at minimum, know what channel to post in to
  :emotes -- optional -- for emoting actions, the list of possible emotes
  :action-args -- optional -- additional arguments of all shapes and
    kinds, passed to the action.
  }"

  ;; Dispatch fn:
  :action)

(defmethod dispatch-action :random-emote-by-key
  [{:keys [msg emotes action-args] :or {:action-args ""}}]
  (log/debug "Dispatching a random emote!")
  (random-emote-by-key action-args msg emotes))

(defmethod dispatch-action :send-message
  [{:keys [msg emotes action-args] :or {:action-args ""}}]
  (send-msg-on-channel! (:channel msg) action-args))

;; Quote actions
(defmethod dispatch-action :add-quote
  [{:keys [msg]}]
  (add-quote! msg))

(defmethod dispatch-action :find-quote-for-name
  [{:keys [msg]}]
  (find-quote-for-name msg))
(defmethod dispatch-action :find-random-quote
  [{:keys [msg]}]
  (find-random-quote msg))

;; Definition actions
(defmethod dispatch-action :add-definition
  [{:keys [msg]}]
  (add-definition! msg))
(defmethod dispatch-action :find-definition
  [{:keys [msg]}]
  (find-definition msg))
(defmethod dispatch-action :send-define-help
  [{:keys [msg]}]
  (send-define-help msg))

;; Responders
(defmethod dispatch-action :bam-responder
  [{:keys [msg emotes]}]
  (bam-responder msg emotes))
(defmethod dispatch-action :oops-responder
  [{:keys [msg emotes]}]
  (oops-responder msg emotes))
(defmethod dispatch-action :omg-responder
  [{:keys [msg emotes]}]
  (omg-responder msg emotes))

;; Help
(defmethod dispatch-action :start-help
  [{:keys [msg]}]
  (help/start-help msg))

;; The actual default
(defmethod dispatch-action :help-or-random-emoji-responder
  [{:keys [msg emotes] :as args}]
  (let [{:keys [user channel]} msg]
    (if (help/should-help? user channel)
      (help/dispatch-help args)
      (random-emoji-responder msg emotes))))

;; A fall-through, just in case, last ditch WTF default.
(defmethod dispatch-action :default
  [args]
  (log/info "No dispatch clause found for:" args))

;; ### Matcher
;; Matches on the combination of [username text], using regex matching. Only one
;; action is baked in: the `random-emoji-responder'. All the rest are loaded at
;; compile time from a configuration file. This means that 1) what blabs
;; responds to is fully configurable, and 2) configuration changes require a
;; restart of blabs.
;;
;; **Remember:** Matching is done by `re-matches`, which only matches if the _entire
;; string_ matches the given regex. Also remember that `match` clauses **must**
;; be static compile-time literals, so you cannot use something defined in the
;; `let` as a regex in the match clauses -- you have to load and `def` them
;; as symbols in the namespace.

(defn load-match-clauses []
  (edn/read-string (slurp (io/resource "commands.edn"))))

(defn build-match-clause
  [cmd {:keys [user regex] :as args-map :or {user "(?s).+"}}]
  (try
    (let [username-re (re-pattern user)
          command-re (re-pattern regex)
          pass-on-args (dissoc args-map :user :command)]
      [[username-re command-re] pass-on-args])
    (catch RuntimeException e
      (do
        (log/error
         (<< "Invalid regex pattern ~{regex} for command map ~{cmd}")
         nil)))))

(defn build-match-clauses [match-specs]
  (reduce concat (for [[cmd spec] match-specs
                       :let [clause (build-match-clause cmd spec)]]
                   clause)))

(defmacro make-matcher []
  (let [raw-clauses (load-match-clauses)
        clauses# (build-match-clauses raw-clauses)]
    `(fn [username# text#]
       (match [username# text#]
              ~@clauses#
              [_# #"!help"] {:action :start-help}
              :else {:action :help-or-random-emoji-responder})
       )))

(def matcher (make-matcher))

;; TODO: turn match-result in to a defrecord; pull useful keys in to it.
(defn message-dispatch
  "Uses regex matching to take a specified action on text."
  [{:keys [user text] :as message :or {text "" user ""}}]
  (let [emotes (load-emotes)
        opt-ins (:opt-ins emotes)
        emoji (load-all-emoji)
        username (slack/get-user-name user)]
    (if-let [match-result (matcher username text)]
      (dispatch-action (assoc match-result :msg message :emotes emotes))
      (log/debug "No match made for text:" text))))
