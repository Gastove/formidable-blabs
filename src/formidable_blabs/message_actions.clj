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
             [config :as config]
             [db :as db]
             [slack :as slack :refer [send-msg-on-channel!]]]
            [formidable-blabs.message-actions.help :as help]
            [taoensso.timbre :as log]))

;; ### Message Action Utilities

(defn load-regex-by-key
  "Given a key k, load a command's regex from the commands config"
  [k]
  (let [re-str (get-in (config/commands) [k :regex])]
    (try
      (if-not (nil? re-str)
        (re-pattern re-str)
        (log/debug "No regex found for key:" k))
      (catch RuntimeException rte
        (log/error
         (<< "Invalid regex pattern ~{re-str} found for key ~{k}")
         rte)))))

(defn bounded-rand-int
  "Returns an int with the following behavior:
  - If the lower and upper bounds are identical, return the lower bound
  - Otherwise, return an int from the range [lower upper)"
  [lower upper]
  (cond
    (= lower upper) lower
    (< (- upper lower) 100) (rand-nth (range lower upper))
    :else (loop [n (rand-int upper)]
            (if (< n lower)
              (recur (rand-int upper))
              n))))

(defn third [coll] (nth coll 2))

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

(defn argenfloop
  [{:keys [action action-args probability rate-limit] :or {:probability 100
                                                           :rate-limit 0}}]
  (let [last-replied (atom (time/date-time 0))
        since-last-millis (* rate-limit 1000)
        n (rand-int 99)
        time? (time/after? (time/now)
                           (time/plus @last-replied
                                      (time/millis since-last-millis)))

        chance? (< n probability)]
    (cond
      time? (do (apply action action-args)
                (swap! last-replied (fn [x] (time/now))))
      chance? (apply action action-args)
      :else (log/debug "No action just yet."))))

;; new throttling
(def last-replied-map (atom {}))

(defn time-to-reply?
  [last-replied throttle-millis]
  (time/after? (time/now) (time/plus last-replied (time/millis throttle-millis))))

(defn pick-and-send-random
  [message possibilities]
  (let [selected (rand-nth possibilities)
        to-chan (:channel message)]
    (send-msg-on-channel! to-chan selected)))

(defn set-last-sent-and-send
  [emote-key message possibilities]
  (swap! last-replied-map assoc emote-key (time/now))
  (pick-and-send-random message possibilities))

(defn set-last-done-and-do
  [emote-key action action-args]
  (swap! last-replied-map assoc emote-key (time/now))
  (apply action action-args))

(defn respond
  "Takes a message, an action to perform on the message, arguments to that
  action, a probability, and a rate limit; responds if: the action isn't rate
  limited by time or the time limit is up; the actions probability checks out.
  Note that time takes precedence over probability."
  [message action action-name action-args rate-limit probability]
  (let [last-replied-time (get @last-replied-map action-name (time/date-time 0))
        throttle-millis (* 1000 rate-limit)]
    (cond
      (time-to-reply? last-replied-time throttle-millis) (set-last-done-and-do action-name action action-args)
      (< (rand-int 99) probability) (apply action action-args)
      :else (log/debug (str "It either isn't time or the probabilities"
                            " didn't shake out, no action yet")))))

(defn respond-with-emoji
  [message emoji rate-limit probability]
  (respond message random-emoji :random-emoji [message emoji] rate-limit probability))

(defn respond-with-random-thing
  "Given a set of things to respond with -- emoji or gifs, for instance --
  respond if: the action isn't rate limited by time or the time limit is up;
  the actions probability checks out. Note that time takes precedence over
  probability."
  [message emote-key rate-limit probability things]
  (respond message pick-and-send-random :emote-key [message things] rate-limit probability))


(defn load-emoji-on-file
  []
  (:names (edn/read-string (slurp (io/resource "emoji_names.edn")))))

(defn load-all-emoji []
  (let [emojis-on-file (load-emoji-on-file)
        custom-emoji (slack/get-custom-emoji)]
    (into emojis-on-file custom-emoji)))

;; ### Quotes
;; Add a quote, search a quote by term, search a quote and return a specific result
(defn add-quote!
  [{:keys [text channel]}]
  (let [regex (load-regex-by-key :add-quote)]
    (if-let [[_ user quote-text] (re-find regex text)]
      (do
        ;; Regex currently might grab an extra space at the end of user; trim.
        (db/record-quote (str/trim user) quote-text)
        (send-msg-on-channel! channel "Quote added!"))
      (do
        (send-msg-on-channel! channel "Erk! Something didn't work. One thousand apologies.")
        (log/error "Either user or text not found?! That's effed up:" text)))))

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

(defn send-nth-quote!
  [quotes text channel send-fn]
  (let [num-quotes (count quotes)
        n (extract-quote-num text num-quotes)
        ;; Vectors are zero-indexed, so nth accordingly.
        {user :user quote-text :quote} (nth quotes (- n 1))
        msg (<< "~{user}: ~{quote-text} (~{n}/~{num-quotes})")]
    (send-fn channel msg)))

(defn find-quote-for-name
  ([m]
   (find-quote-for-name m
                        send-msg-on-channel!
                        db/find-quote-by-user-or-term
                        (load-regex-by-key :find-quote-for-name)))

  ([{:keys [text channel]} send-fn lookup-fn regex]
   (if-let [[_ untrimmed-name] (re-find regex text)]
     (let [name-to-find (str/trim untrimmed-name)
           result-seq (lookup-fn name-to-find)]
       (if-not (empty? result-seq)
         (send-nth-quote! result-seq text channel send-fn)
         (log/debug (<< "No quote found for ~{name-to-find}")))))))

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
   (let [regex (load-regex-by-key :add-definition)]
     (if-let [[_ term definition] (re-find regex text)]
       (do
         (db/record-definition term definition)
         (send-fn channel (<< "Okay! `~{term}` is now defined as, `~{definition}`")))
       (do
         (send-fn "Erk! Something went wrong. I couldn't define that.")
         (log/error "Couldn't get a definition out of:" text))))))

(defn send-define-help
  [{:keys [text channel]}]
  (let [msg (str/join " "
                      ["I didn't get that. To define a term, use the command"
                       "format, `!define term: definition`"])]
    (send-msg-on-channel! channel msg)))

;; ### Definition Lookup
;; You may be thinking, `find-defintion` looks an _awful lot_ like
;; `find-quote-for-name` -- and you're right. The important difference
;; is: you can define nearly anything, so the regex must match on `.+` to be
;; sure of getting everything -- which means, `term` needs to be parsed out with
;; `second`. Haven't figured out _quite_ how to abstract this all together yet.

(defn find-definition
  ([m] (find-definition m send-msg-on-channel! db/find-definiton-by-term))
  ([{:keys [text channel]} send-fn lookup-fn]
   (let [regex (load-regex-by-key :find-definition)
         m (re-find regex text)
         term (or (second m) (third m))
         result-seq (lookup-fn term)
         num-defs (count result-seq)
         n (extract-definition-number text num-defs)
         d (nth result-seq (- n 1))
         {defd-on :defined-at definition :definition} d
         msg (<< "~{term}:\n> ~{definition}\n Definition ~{n} of ~{num-defs}; last defined ~{defd-on}")]
     (send-fn channel msg))))

;; Not currently used. Does a handy thing, but unclear if it does a necessary
;; thing.
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
  :rate-limit -- optional -- we should wait at least this many seconds between
    occurences of an actions; defaults to 0
  :probability -- optional -- chance in 100 that an action occurs; defaults to 100
  }"

  ;; Dispatch fn:
  :action)

;; `:random-emote-by-key' randomly selects an entry from its `:action-args',
;; which should be a vector [] of candidates, like so:
;; ["yes", "no", "maybe"]
(defmethod dispatch-action :random-emote-by-key
  [{:keys [msg emotes action action-args rate-limit probability]
    :or {action-args ""
         rate-limit 0
         probability 100}}]
  (log/debug "Dispatching a random emote!")
  (respond-with-random-thing msg action rate-limit probability action-args))

;; `:send-message' sends the message found in `:action-args'
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

;; Help
(defmethod dispatch-action :start-help
  [{:keys [msg]}]
  (help/start-help msg))

;; The actual default
(defmethod dispatch-action :help-or-random-emoji-responder
  [{:keys [msg emotes action action-args rate-limit probability]
    :or {action-args ""
         rate-limit 0
         probability 100}
    :as args}]
  (let [{:keys [user channel]} msg
        emoji (load-all-emoji)]
    (if (help/should-respond-with-help? user channel)
      (help/dispatch-help args)
      (respond-with-emoji msg emoji rate-limit probability))))

;; A fall-through, just in case, last ditch WTF default.
(defmethod dispatch-action :default
  [args]
  (log/info "No dispatch clause found for:" args))

;; ### Matcher
;; Matches on the combination of [username text], using regex matching. Only two
;; actions are baked in: the `random-emoji-responder' and the help system. All
;; the rest are loaded at compile time from a configuration file. This means
;; that 1) what blabs responds to is fully configurable, and 2) configuration
;; changes require a restart of blabs.
;;
;; **Remember:** Matching is done by `re-matches`, which only matches if the _entire
;; string_ matches the given regex. Also remember that `match` clauses **must**
;; be static compile-time literals, so you cannot use something defined in the
;; `let` as a regex in the match clauses -- you have to load and `def` them
;; as symbols in the namespace.

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
                       :when (not (some #{cmd} [:nomad/environment :nomad/hostname :nomad/instance :random-emoji]))
                       :let [clause (build-match-clause cmd spec)]]
                   clause)))

(defmacro make-matcher []
  (let [raw-clauses (config/commands)
        emoji-args (:random-emoji raw-clauses)
        clauses# (build-match-clauses raw-clauses)
        else-clause# (merge {:action :help-or-random-emoji-responder} emoji-args)]
    `(fn [username# text#]
       (match [username# text#]
              ~@clauses#
              [_# #"!help"] {:action :start-help}
              :else ~else-clause#))))

(def matcher (make-matcher))

;; TODO: turn match-result in to a defrecord; pull useful keys in to it.
(defn message-dispatch
  "Uses regex matching to take a specified action on text."
  [{:keys [user text] :as message :or {text "" user ""}}]
  (let [emoji (load-all-emoji)
        username (slack/get-user-name user)]
    (if-let [match-result (matcher username text)]
      (dispatch-action (assoc match-result :msg message))
      (log/debug "No match made for text:" text))))
