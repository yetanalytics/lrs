(ns com.yetanalytics.lrs.xapi.activities-test
  (:require
   [clojure.test :refer [deftest is testing] :include-macros true]
   [clojure.spec.test.alpha :as stest :include-macros true]
   [com.yetanalytics.test-support :refer [failures stc-opts]]
   [com.yetanalytics.lrs.xapi.activities :as ac]))

(deftest merge-activity-test
  (is (empty? (failures
               (stest/check `ac/merge-activity
                            {stc-opts
                             {:num-tests 100 :max-size 3}})))))
