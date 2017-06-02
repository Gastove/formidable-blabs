(ns formidable-blabs.message-actions.help-test
  (:require [formidable-blabs.message-actions.help :as h]
            [clojure.test :refer :all]
            [clojure.string :as str]))

(def test-commands
  {:shout {:help "Shout, shout"}
   :let-it-all-out {:help "let it all out!"}
   :cmon {:not-help "Not talking to you"}})

(deftest topics-test
  (testing "Can we correctly list only topics with help keys?"
    (let [test-topic-set (set (h/make-topic-list test-commands))
          expected #{:shout :let-it-all-out}]
      (is (= expected test-topic-set)
          "Only specs with help keys should be listed"))))

(deftest predicates-test
  (let [test-user-id "U0123456"
        test-channel "D0123456"
        test-dm-channel "DM123456"]
    (testing "Does a user have an active help session?"
      (is (false? (h/user-help-session-active? test-user-id))
          "No help session has been set, do not respond")
      (swap! h/help-channels assoc test-user-id test-dm-channel)
      (is (true? (h/user-help-session-active? test-user-id))
          "Help session is set, respond")
      (is (true? (h/should-respond-with-help? test-user-id test-dm-channel))
          "We should help if the message is from a user on their DM channel")
      (is (false? (h/should-respond-with-help? test-user-id test-channel))
          "We should not respond to a user with an active help session *not* on their DM channel")
      (reset! h/help-channels {}))))

(def test-indexed-commands
  {0 :shout
   1 :let-it-all-out})

(deftest formatting-test
  (testing "Can we convert from keys to names and back?"
    (let [cmd-str "do the thing"
          cmd-key :do-the-thing]
      (is (= cmd-key (h/format-text-as-command-name cmd-str)))
      (is (= cmd-str (h/format-command-key-as-text cmd-key)))))
  (testing "Can we format commands correctly?"
    (let [formatted-cmds (h/format-topic-list test-indexed-commands)
          expected (str/join \newline ["[0] shout" "[1] let it all out"])]
      (is (= formatted-cmds expected)))))
