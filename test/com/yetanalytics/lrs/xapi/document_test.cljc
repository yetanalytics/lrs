(ns com.yetanalytics.lrs.xapi.document-test
  (:require [clojure.test :refer [deftest is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(deftest documents-priority-map-test
  (is (empty?
       (failures
        (stest/check
         `doc/documents-priority-map {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest merge-or-replace-test
  (is (empty?
       (failures
        (stest/check
         `doc/merge-or-replace {stc-opts {:num-tests 100 :max-size 3}})))))
