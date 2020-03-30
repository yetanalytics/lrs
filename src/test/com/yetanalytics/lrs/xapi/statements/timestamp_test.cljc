(ns com.yetanalytics.lrs.xapi.statements.timestamp-test
  (:require [clojure.test :refer [deftest is are testing] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]
            )
  #?(:clj (:import [java.time Instant])))

(deftest normalize-inst-test
  (testing "expected output format"
    (is (= "1970-01-01T00:00:00.000000000Z"
           (timestamp/normalize-inst #?(:clj Instant/EPOCH
                                        :cljs (js/Date. 0)))))))

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
      "1970-01-01T00:00:00"            "1970-01-01T00:00:00.000000000Z"
      ;; hmm
      "1970"                           "1970-01-01T00:00:00.000000000Z"
      "1969-12-31T19:00:00-0500"       "1970-01-01T00:00:00.000000000Z"
      ;; LOUD HMMM
      "19691231T19:00:00-0500"         "1970-01-01T00:00:00.000000000Z"
      )))
