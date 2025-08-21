(ns com.yetanalytics.lrs.xapi.activities-test
  (:require
   [clojure.test :refer [deftest is testing] :include-macros true]
   [clojure.spec.test.alpha :as stest :include-macros true]
   [com.yetanalytics.test-support :refer [failures stc-opts]]
   [com.yetanalytics.lrs.xapi.activities :as ac]))

(def act-id-only
  {"id" "http://www.example.com/tincan/activities/multipart"})

(def act-full
  {"id"         "http://www.example.com/tincan/activities/multipart"
   "objectType" "Activity"
   "definition" {"type"        "http://www.example.com/activity-types/test"
                 "name"        {"en-US" "Multi Part Activity"
                                "zh-CN" "多元部分Activity"}
                 "description" {"en-US" "Multi Part Activity Description"
                                "zh-CN" "多元部分Activity的简述"}}})

(def act-en
  {"id"         "http://www.example.com/tincan/activities/multipart"
   "objectType" "Activity"
   "definition" {"type"        "http://www.example.com/activity-types/test"
                 "name"        {"en-US" "Multi Part Activity"}
                 "description" {"en-US" "Multi Part Activity Description"}}})

(def act-zh
  {"id"         "http://www.example.com/tincan/activities/multipart"
   "objectType" "Activity"
   "definition" {"type"        "http://www.example.com/activity-types/test"
                 "name"        {"zh-CN" "多元部分Activity"}
                 "description" {"zh-CN" "多元部分Activity的简述"}}})

(def act-matching
  {"id" "aaa://aaa.aaa.aaa/aaa"
   "objectType" "Activity"
   "definition"
   {"interactionType" "matching"
    "source"          [{"id" "0"}]
    "target"          [{"id" "A"}]}})

(def act-numeric
  {"id" "aaa://aaa.aaa.aaa/aaa"
   "objectType" "Activity"
   "definition"
   {"interactionType" "numeric"}})

(deftest merge-activity-test
  (testing "merging activities with differing detail levels"
    (is (= (ac/merge-activity act-id-only act-full)
           (ac/merge-activity act-full act-id-only)
           act-full)))
  (testing "merging activities with disjoint languages"
    (is (= (ac/merge-activity act-en act-zh)
           (ac/merge-activity act-zh act-en)
           act-full)))
  (testing "can change type"
    (let [act-new-type (assoc-in act-full ["definition" "type"]
                                 "http://www.example.com/activity-types/test2")]
      (is (= (ac/merge-activity act-full act-new-type)
             act-new-type))))
  (testing "interaction activity atomic updates"
    (is (= (ac/merge-activity act-matching act-numeric)
           act-numeric)
        (= (ac/merge-activity act-numeric act-matching)
           act-matching)))
  (testing "merging activities (generative)"
    (is (empty? (failures
                 (stest/check `ac/merge-activity
                              {stc-opts
                               {:num-tests 100 :max-size 3}}))))))
