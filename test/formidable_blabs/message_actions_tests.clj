(ns formidable-blabs.message-actions-tests
  (:require [formidable-blabs.message-actions :as ma]
            [clojure.test :as t :refer [deftest testing is]]))

(deftest test-utilities
  (testing "Can we return correct, fast results for bounded-rand-int?"
    (is (= (ma/bounded-rand-int 1 1) 1)
        "Bounded between 1 and 1 should be 1")
    ;; (let [nums (repeatedly 1000 #(ma/bounded-rand-int 1 100))
    ;;       res (filter #(< % 101))]
    ;;   (is (empty? res)
    ;;       "Upper bound should be respected"))
    ;; (let [nums (repeatedly 1000 #(ma/bounded-rand-int 50 100))
    ;;       res (filter #(> % 1))]
    ;;  (is (= 1000 (count res))
    ;;      ("Lower bound should be respected")))
    ))

(deftest quoting
  (testing "Can we return an int from a string that might not have one?"
    (let [has-int "!q 2 gastove"
          has-no-int "!q gastove"]
      (is (= (ma/extract-quote-num has-int 1) 2))
      (is (= (ma/extract-quote-num has-no-int 1) 1))))
  (testing "Do we correctly handle ridiculous numbers?"
    (let [bad "!q 0 gastove"
          terribad "!q -500 gastove"
          wat "!q 0000000000 gatove"
          huge "!q 5000000 gastove"]
      (is (= (ma/extract-quote-num bad 1) 1))
      (is (= (ma/extract-quote-num terribad 1) 1))
      (is (= (ma/extract-quote-num wat 1) 1))
      (is (= (ma/extract-quote-num wat 10) 10)))))
