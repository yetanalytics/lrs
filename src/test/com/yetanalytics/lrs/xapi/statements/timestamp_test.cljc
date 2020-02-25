(ns com.yetanalytics.lrs.xapi.statements.timestamp-test
  (:require [clojure.test :refer [deftest is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]
            ))

(deftest normalize-test
  (is (empty?
       (failures
        (stest/check
         `timestamp/normalize {stc-opts {}})))))
