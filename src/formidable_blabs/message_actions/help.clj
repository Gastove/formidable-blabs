(ns formidable-blabs.message-actions.help
  (:require [clojure
             [edn :as edn]
             [string :as str]]
            [clojure.core.strint :refer [<<]]
            [clojure.java.io :as io]
            [formidable-blabs.slack :as slack]
            [formidable-blabs.config :as config]
            [taoensso.timbre :as log]))

;; ### The `help' ns

;; This is Blabs' help system. It's interactive, and based on DMs (so it wont
;; clog up the main chat channels Blabs is in; also for simplicity of
;; implementation). Blabs loads its help from its main commands configuration
;; file, commands.end, and wont offer help on any command without a `:help' key
;; in its config map.
;;
;; In the future, it will be possible to (re-)define the help text for a command
;; interactively, but at present that feature has yet to be implemented.
;;
;; #### Planned Improvements
;; TODO: Allow (re-)defining help, interactively; maybe don't let just _any_
;; user do this? Can check a users' admin flag, but using nomad for the config
;; file makes this... interesting.
;; TODO: Fuzzy-match help > strict text match help

(def commands-map (config/commands))

(defn make-topic-list
  "Takes a map of commands of the format {command-name {command-spec}}
  Returns the names of every command with a :help key defined."
  [commands]
  (->> commands
       (filter (fn [[_ spec]] (not (nil? (:help spec)))))
       (keys)))

(defn make-indexed-topics
  [topics]
  (into {} (map-indexed (fn [idx k] [idx k]) topics)))

(def commands-by-index (-> commands-map
                           (make-topic-list)
                           (make-indexed-topics)
                           (sort)))

;; Formatting
(defn format-text-as-command-name
  "Takes a string with a name like `find quote for name'
  Returns a kebab-case keyword like `:find-quote-for-name'"
  [t]
  (-> t
      (str/replace #"\s" "-")
      (keyword)))

(defn format-command-key-as-text
  "Takes a keyword like :find-quote-for-name
  Returns text like `find quote for name'"
  [cmd-key]
  (-> cmd-key
      (name)
      (str/replace #"\-" " ")))

(defn format-topic-list
  "Takes the list of key names returned by `make-topic-list'.
  Returns a string of topics formatted as `[Index of topic name] topic name'"
  [topic-list]
  (let [pieces (for [[idx cmd] topic-list
                     :let [cmd-name (format-command-key-as-text cmd)]]
                 (<< "[~{idx}] ~{cmd-name}"))]
    (str/join \newline pieces)))

(def help-channels (atom {}))

(defn get-help-channel-for-user
  [user-id]
  (@help-channels user-id))

(defn make-opening-message
  "Creates the initial message a user will receive on their DM channel with
  Blabs when they initiate a help session."
  [indexed-command-names]
  (let [topics (format-topic-list indexed-command-names)
        opening "Hi! What can I help you with? Here's the commands I've got:"
        closing (str "Which command would you like to know more about?"
                     "You can say a number or an exact term, or say \"done\""
                     "to end help.")]
    (str/join \newline [opening topics closing])))

(defn start-help
  "Initiates a help session. Opens a direct message channel, then
  adds the pair `user-id dm-channel' to the help-channels atom."
  [{user-id :user}]
  (let [dm-channel-resp (slack/open-direct-message-channel user-id)
        dm-channel (get-in dm-channel-resp [:channel :id])
        opening-message (make-opening-message commands-by-index)]
    (slack/post-message dm-channel opening-message)
    (swap! help-channels assoc user-id dm-channel)))

(defn user-help-session-active?
  "Do we currently have a `user-id dm-channel' pair in the
  help-channels atom for a given user-id?"
  [user-id]
  (contains? @help-channels user-id))

(defn should-respond-with-help?
  "We should respond with help if a user-id has an active help session
  _and_ the channel we've received a message from that user on is
  that users'  DM channel."
  [user-id channel]
  (and (user-help-session-active? user-id)
       (= (@help-channels user-id) channel)))

(defn load-help-by-key
  "Returns either the help text for a command, or a polite apology."
  [k]
  (if-let [help-text (get-in commands-map [k :help])]
    help-text
    (<< "I'm sorry, I don't know how to help you with \"~{k}\". Try again?'")))

(defn get-help-by-number [n]
  (let [idx (read-string n)
        k (commands-by-index idx)]
    (load-help-by-key k)))

(defn get-help-by-string [s]
  (let [k (format-text-as-command-name s)]
    (load-help-by-key k)))

(defn end-help
  "End the help session for a user by removing that users ID and dm-channel from
  the help-channels atom"
  [user-id]
  (let [help-channel (get-help-channel-for-user user-id)]
    (slack/send-msg-on-channel! help-channel "Okie dokie!")
    (swap! help-channel dissoc user-id)))

(defn dispatch-help
  "Entry point for a current help session. Checks whether a user has provided a
  numeric response or a text response and tries to provide help accordingly."
  [[{message :msg}]]
  (let [{:keys [user text]} message
        help-channel (get-help-channel-for-user user)]
    (if (= text "done")
      (end-help help-channel)
      (let [help-text (if (number? (read-string text))
                        (get-help-by-number text)
                        (get-help-by-string text))]
        (slack/send-msg-on-channel! help-channel help-text)))))
