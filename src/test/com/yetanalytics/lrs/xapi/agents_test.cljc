(ns com.yetanalytics.lrs.xapi.agents-test
  (:require [clojure.test :refer [deftest is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.agents :as ag]))

(deftest find-ifi-test
  (is (empty?
       (failures
        (stest/check `ag/find-ifi
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest ifi-match?-test
  (is (empty?
       (failures
        (stest/check `ag/ifi-match?
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest person-conj-test
  (is (empty?
       (failures
        (stest/check `ag/person-conj
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest person-test
  (is (empty?
       (failures
        (stest/check `ag/person
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest actor-seq-test
  (is (empty?
       (failures
        (stest/check `ag/actor-seq
                     {stc-opts {:num-tests 100 :max-size 3}})))))
