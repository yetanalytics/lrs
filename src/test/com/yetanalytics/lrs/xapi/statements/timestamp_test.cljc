(ns com.yetanalytics.lrs.xapi.statements.timestamp-test
  (:require [clojure.test :refer [deftest is are testing] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp])
  #?(:clj (:import [java.time Instant])))

(deftest normalize-inst-test
  (testing "expected output format"
    (is (= "1970-01-01T00:00:00.000000000Z"
           (timestamp/normalize-inst #?(:clj Instant/EPOCH
                                        :cljs (js/Date. 0)))))))
(deftest parse-stamp-test
  ;; "Now you have two problems"
  (testing "generative function tests"
    (is (empty?
         (failures
          (stest/check
           `timestamp/parse-stamp {stc-opts {}}))))))

(deftest normalize-test
  (testing "generative function tests"
    (is (empty?
         (failures
          (stest/check
           `timestamp/normalize {stc-opts {}})))))

  (testing "expected ouput format"
    (are [stamp-in stamp-out]
        (= stamp-out
           (timestamp/normalize stamp-out))
      "1970-01-01T00:00:00.000000000Z" "1970-01-01T00:00:00.000000000Z"
      "1970-01-01T00:00:00.000Z"       "1970-01-01T00:00:00.000000000Z"
      "1970-01-01T00:00:00.001Z"       "1970-01-01T00:00:00.001000000Z"
      ;; tests timing in cljs
      "1970-01-01T00:00:00.101010101Z" "1970-01-01T00:00:00.101010101Z"
      "1970-01-01T00:00:00"            "1970-01-01T00:00:00.000000000Z"

      ;; offset
      "2020-03-31T15:12:03+00:00"      "2020-03-31T15:12:03.000000000Z"
      "2020-03-31T15:12:03+05:00"      "2020-03-31T20:12:03.000000000Z"
      ;; stuff below this line not covered by xapi-schema
      ;; hmm
      "1970"                           "1970-01-01T00:00:00.000000000Z"
      "1969-12-31T19:00:00-0500"       "1970-01-01T00:00:00.000000000Z"
      ;; LOUD HMMM
      "19691231T19:00:00-0500"         "1970-01-01T00:00:00.000000000Z")))

(deftest stamp-now-test
  (testing "generative function tests"
    (is (empty?
         (failures
          (stest/check
           `timestamp/stamp-now {stc-opts {}}))))))

(deftest stamp-seq-test
  (testing "simple expectations"
    (let [stamps (take 1000 (timestamp/stamp-seq))]
      (testing "stamps are normalized"
        (is (every? (fn normalized?
                      [^String stamp]
                      (and (= 30 (count stamp))
                           (.endsWith stamp "Z")))
                    stamps)))
      (testing "stamps are monotonic"
        (is (apply distinct? stamps))
        (is (= stamps (sort stamps)))))))
