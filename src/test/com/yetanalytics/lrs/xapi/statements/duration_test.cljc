(ns com.yetanalytics.lrs.xapi.statements.duration-test
  (:require [clojure.test :refer [deftest is are testing] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.statements.duration :refer [normalize-duration]]))

(deftest normalize-duration-test
  (testing "generative function tests"
    (is (empty?
         (failures
          (stest/check
           `normalize-duration {})))))

  (testing "expected ouput format"
    (are [dur-in dur-out]
         (= dur-out
            (normalize-duration dur-in))
      "PT0S"              "PT0S"
      "PT0.001S"          "PT0.00S"
      "PT0.009S"          "PT0.00S"
      "PT0.01S"           "PT0.01S"
      "PT0.019S"          "PT0.01S"
      "PT0.1S"            "PT0.1S"
      "PT1S"              "PT1S"
      "PT1.2345678S"      "PT1.23S"
      "PT1M"              "PT1M"
      "PT1M1.2345678S"    "PT1M1.23S"
      "PT1H"              "PT1H"
      "PT1H1M1.2345678S"  "PT1H1M1.23S"
      "P1D"               "P1D"
      "P1DT12H36M0.1237S" "P1DT12H36M0.12S")))
