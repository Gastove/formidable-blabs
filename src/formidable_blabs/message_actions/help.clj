(ns formidable-blabs.message-actions.help
  (:require [clojure
             [edn :as edn]
             [string :as str]]
            [clojure.core.strint :refer [<<]]
            [clojure.java.io :as io]
            [formidable-blabs.slack :as slack]
            [taoensso.timbre :as log]))

;; TODO: move this to config, or something
(def commands-map (edn/read-string (slurp (io/resource "commands.edn"))))

(def help-channels (atom {}))

(defn get-help-channel-for-user
  [user-id]
  (@help-channels user-id))

(declare make-topic-list format-topic-list)
(defn make-opening-message
  [commands]
  (let [topics (-> commands
                   (make-topic-list)
                   (format-topic-list))]
    (str/join \newline ["Hi! What can I help you with? Here's the commands I've got:"
                        topics
                        "Which command would you like to know more about? You can say a number or an exact term, or say \"done\" to end help."])))

(defn start-help
  [{user-id :user}]
  (let [dm-channel-resp (slack/open-direct-message-channel user-id)
        dm-channel (get-in dm-channel-resp [:channel :id])
        opening-message (make-opening-message commands-map)]
    (slack/post-message dm-channel opening-message)
    (swap! help-channels assoc user-id dm-channel)))

(defn make-topic-list [commands]
  (->> commands
       (filter (fn [[_ spec]] (not (nil? (:help spec)))))
       (keys)))

(defn format-topic-list
  [topic-list]
  (->> topic-list
       (map name)
       (map #(str/replace % #"\-" " "))
       (map-indexed (fn [idx topic] (<< "[~{idx}] ~{topic}")))
       (str/join \newline)))

(defn user-help-session-active?
  [user]
  (contains? @help-channels user))

(defn should-help?
  [user channel]
  (and (user-help-session-active? user)
       (= (@help-channels user) channel)))

(defn format-text
  [t]
  (-> t
      (str/replace #"\s" "-")
      (keyword)))

(defn load-help-by-key
  [k]
  (if-let [help-text (get-in commands-map [k :help])]
    help-text
    (<< "I'm sorry, I don't know how to help you with \"~{k}\". Try again?'")))

(defn get-help-by-number [n]
  (let [idx (read-string n)
        k (nth (keys commands-map) idx)]
    (load-help-by-key k)))

(defn get-help-by-string
  [s]
  (let [k (format-text s)]
    (load-help-by-key k)))

(defn end-help
  [user-id]
  (let [help-channel (get-help-channel-for-user user-id)]
    (slack/send-msg-on-channel! help-channel "Okie dokie!")
    (swap! help-channel dissoc user-id)
    (slack/close-direct-message-channel help-channel)))

(defn dispatch-help
  [{message :msg}]
  (let [{:keys [user text]} message
        help-channel (get-help-channel-for-user user)]
   (if (= text "done")
     (end-help help-channel)
     (let [help-text (if (number? (read-string text))
                       (get-help-by-number text)
                       (get-help-by-string text))]
       (slack/send-msg-on-channel! help-channel help-text)))))
