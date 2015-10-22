(ns formidable-blabs.message-actions-tests
  (:require [formidable-blabs.message-actions :as ma]
            [clojure.test :as t :refer [deftest testing is]]))

(deftest test-utilities
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

(deftest number-extraction
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
