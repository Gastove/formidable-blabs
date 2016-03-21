(ns formidable-blabs.message-actions-tests
  (:require [formidable-blabs.message-actions :as ma]
            [clojure.test :as t :refer [deftest testing is]]))

(deftest utilities-test
  (testing "Can we return correct, fast results for bounded-rand-int?"
    (is (= (ma/bounded-rand-int 1 1) 1)
        "Bounded between 1 and 1 should be 1")
    (let [nums (repeatedly 1000 #(ma/bounded-rand-int 1 100))
          res (filter #(> % 100) nums)]
      (is (empty? res)
          "Upper bound should be respected"))
    (let [nums (repeatedly 1000 #(ma/bounded-rand-int 50 100))
          res (filter #(< % 50) nums)]
      (is (empty? res)
          "Lower bound should be respected"))))

(deftest number-extraction-test
  (let [r #"(\d+)"]
    (testing "Can we return an int from a string that might not have one?"
      (let [has-int "5"
            has-no-int "cat"]
        (is (= (ma/extract-num-with-regex has-int 1 r) 1))
        (is (= (ma/extract-num-with-regex has-no-int 1 r) 1))))
    (testing "Do we correctly handle ridiculous numbers?"
      (let [bad "0"
            terribad "-500"
            wat "0000000000"
            huge "5000000"]
        (is (= (ma/extract-num-with-regex bad 1 r) 1))
        (is (= (ma/extract-num-with-regex terribad 1 r) 1))
        (is (= (ma/extract-num-with-regex wat 1 r) 1))
        (is (= (ma/extract-num-with-regex huge 10 r) 10))))))

(defn lookup-fn-util
  [expected]
  (let [result [{:defined-at "now" :definition "hi"}
                {:defined-at "then" :definition "bye"}]]
    (fn [got]
      (is (= expected got))
      result)))

(defn send-fn [& args] args)

(deftest find-definition-test
  (testing "Can we find without an index?"
    (let [incoming {:text "!whatis cat" :channel "poot"}
          expected '("poot" "cat:\n> bye\n Definition 2 of 2; last defined then")
          lookup-fn (lookup-fn-util "cat")
          res (ma/find-definition incoming send-fn lookup-fn)]
      (is (= res expected))))
  (testing "Can we find with an index?"
    (let [incoming {:text "!whatis cat 1" :channel "poot"}
          expected '("poot" "cat:\n> hi\n Definition 1 of 2; last defined now")
          lookup-fn (lookup-fn-util "cat")
          res (ma/find-definition incoming send-fn lookup-fn)]
      (is (= res expected)))))

(defn lookup-quote-util
  [expected]
  (let [result [{:user "Elvis" :quote "oh yeah"}
                {:user "Bruce" :quote "Claptu verata nekto"}]]
    (fn [got]
      (is (= expected got))
      result)))

(def quote-strings ["Elvis: oh yeah (1/2)"
                    "Bruce: Claptu verata nekto (2/2)"])

(deftest find-quote-for-name-test
  (testing "Can we get a quote for a user?"
   (let [incoming {:text "!quote Elvis" :quote "poot"}
         lookup-fn (lookup-quote-util "Elvis")
         res (ma/find-quote-for-name incoming send-fn lookup-fn)]
     ;; Make sure there is a returned quote, and it's in the list.
     (= (not (nil? (some #{(second res)} quote-strings))))))
  (testing "Can we get a quote for a user by number?"
    (let [incoming {:text "!quote Elvis 2" :quote "poot"}
          lookup-fn (lookup-quote-util "Elvis")
          expected '("poot" (second quote-strings))
          res (ma/find-quote-for-name incoming send-fn lookup-fn)]
      ;; Make sure there is a returned quote, and it's in the list.
      (= res expected))))
