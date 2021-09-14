(ns com.yetanalytics.lrs.xapi.statements-test
  (:require [clojure.test :refer [deftest is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.statements :as ss]))

(deftest normalize-id-test
  (is (empty?
       (failures
        (stest/check `ss/normalize-id
                     {stc-opts {}})))))

(deftest get-id-test
  (is (empty?
       (failures
        (stest/check `ss/get-id
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest stored-inst-test
  (is (empty?
       (failures
        (stest/check `ss/stored-inst
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statements-priority-map-test
  (is (empty?
       (failures
        (stest/check `ss/statements-priority-map
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest now-stamp-test
  (is (empty?
       (failures
        (stest/check `ss/now-stamp
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest fix-context-activities-test
  (is (empty?
       (failures
        (stest/check `ss/fix-context-activities
                     {stc-opts {:num-tests 1 :max-size 3}})))))

(deftest fix-statement-context-activities-test
  (is (empty?
       (failures
        (stest/check `ss/fix-statement-context-activities
                     {stc-opts {:num-tests 1 :max-size 3}})))))

(deftest prepare-statement-test
  (is (empty?
       (failures
        (stest/check `ss/prepare-statement
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest dissoc-lrs-attrs-test
  (is (empty?
       (failures
        (stest/check `ss/dissoc-lrs-attrs
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest canonize-lmap-test
  (is (empty?
       (failures
        (stest/check `ss/canonize-lmap
                     {stc-opts {:num-tests 5 :max-size 3}})))))

(deftest format-canonical-test
  (is (empty?
       (failures
        (stest/check
         `ss/format-canonical {stc-opts {:num-tests 1 :max-size 3}})))))


(deftest format-statement-ids-test
  (is (empty?
       (failures
        (stest/check `ss/format-statement-ids
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest format-ids-test
  (is (empty?
       (failures
        (stest/check `ss/format-ids
                     {stc-opts {:num-tests 1 :max-size 2}})))))

(deftest collect-context-activities-test
  (is (empty?
       (failures
        (stest/check `ss/collect-context-activities
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-related-activities-test
  (is (empty?
       (failures
        (stest/check `ss/statement-related-activities
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-related-activity-ids-test
  (is (empty?
       (failures
        (stest/check `ss/statement-related-activity-ids
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-agents-narrow-test
  (is (empty?
       (failures
        (stest/check `ss/statement-agents-narrow
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-agents-broad-test
  (is (empty?
       (failures
        (stest/check `ss/statement-agents-broad
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-agents-test
  (is (empty?
       (failures
        (stest/check `ss/statement-agents
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-ref?-test
  (is (empty?
       (failures
        (stest/check `ss/statement-ref?
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest all-attachment-hashes-test
  (is (empty?
       (failures
        (stest/check `ss/all-attachment-hashes
                     {stc-opts {:num-tests 2 :max-size 2}})))))

(deftest statement-rel-docs-test
  (is (empty?
       (failures
        (stest/check `ss/statement-rel-docs
                     {stc-opts {:num-tests 10 :max-size 3}})))))
