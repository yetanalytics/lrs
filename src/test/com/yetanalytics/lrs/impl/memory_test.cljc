(ns com.yetanalytics.lrs.impl.memory-test
  (:require [clojure.test :as test :refer [deftest is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.impl.memory :as mem]))

(deftest store-ref-test
  (is (empty?
       (failures
        (stest/check `mem/store-ref
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest store-attachments-test
  (is (empty?
       (failures
        (stest/check `mem/store-attachments
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest store-activity-test
  (is (empty?
       (failures
        (stest/check `mem/store-activity
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest store-activities-test
  (is (empty?
       (failures
        (stest/check `mem/store-activities
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest store-agent-test
  (is (empty?
       (failures
        (stest/check `mem/store-agent
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest store-agents-test
  (is (empty?
       (failures
        (stest/check `mem/store-agents
                     {stc-opts {:num-tests 3 :max-size 2}})))))

(deftest transact-document-test
  (is (empty?
       (failures
        (stest/check `mem/transact-document
                     {stc-opts {:num-tests 3 :max-size 2}})))))

(deftest get-document-test
  (is (empty?
       (failures
        (stest/check `mem/get-document
                     {stc-opts {:num-tests 3 :max-size 2}})))))

(deftest get-document-ids-test
  (is (empty?
       (failures
        (stest/check `mem/get-document-ids
                     {stc-opts {:num-tests 3 :max-size 2}})))))

(deftest delete-document-test
  (is (empty?
       (failures
        (stest/check `mem/delete-document
                     {stc-opts {:num-tests 3 :max-size 2}})))))

(deftest delete-documents-test
  (is (empty?
       (failures
        (stest/check `mem/delete-documents
                     {stc-opts {:num-tests 3 :max-size 2}})))))

(deftest empty-state-test
  (is (empty?
       (failures
        (stest/check `mem/empty-state
                     {stc-opts {:num-tests 1 :max-size 3}})))))

(deftest transact-statements-test
  (is (empty?
       (failures
        (stest/check `mem/transact-statements
                     {stc-opts {:num-tests 3 :max-size 1}})))))

(deftest statements-seq-test
  (is (empty?
       (failures
        (stest/check `mem/statements-seq
                     {stc-opts {:num-tests 3 :max-size 1}})))))

(deftest fixture-state-test
  (is (empty?
       (failures
        (stest/check `mem/fixture-state
                     {stc-opts {:num-tests 1 :max-size 1}})))))

(deftest new-lrs-test
  (is (empty?
       (failures
        (stest/check `mem/new-lrs
                     {stc-opts {:num-tests 1}})))))
