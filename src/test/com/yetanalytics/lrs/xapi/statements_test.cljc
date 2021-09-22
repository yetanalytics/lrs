(ns com.yetanalytics.lrs.xapi.statements-test
  (:require [clojure.test :refer [deftest testing is] :include-macros true]
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

(deftest dissoc-statement-properties-test
  (is (empty?
       (failures
        (stest/check `ss/dissoc-statement-properties
                     {stc-opts {:num-tests 10 :max-size 3}})))))

(deftest statement-immut-equal?-test
  (is (empty?
       (failures
        (stest/check `ss/statement-immut-equal?
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dissoc Statement property test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sample-id
  "030e001f-b32a-4361-b701-039a3d9fceb1")

(def sample-group
  {"mbox"       "mailto:sample.group@example.com"
   "name"       "Sample Group"
   "objectType" "Group"
   "member"     [{"mbox" "mailto:agent2@example.com"
                  "name" "Agent 2"}
                 {"mbox" "mailto:agent2@example.com"
                  "name" "Agent 2"}
                 {"mbox" "mailto:agent1@example.com"
                  "name" "Agent 1"}]})

(def sample-group-dissoc
  {"mbox"       "mailto:sample.group@example.com"
   "name"       "Sample Group"
   "objectType" "Group"
   "member"     #{{"mbox" "mailto:agent2@example.com"
                   "name" "Agent 2"}
                  {"mbox" "mailto:agent1@example.com"
                   "name" "Agent 1"}}})

(def sample-verb
  {"id"      "http://adlnet.gov/expapi/verbs/answered"
   "display" {"en-US" "answered"}})

(def sample-verb-dissoc
  {"id" "http://adlnet.gov/expapi/verbs/answered"})

(def sample-activity
  {"id"         "http://www.example.com/tincan/activities/multipart"
   "objectType" "Activity"
   "definition" {"name"        {"en-US" "Multi Part Activity"}
                 "description" {"en-US" "Multi Part Activity Description"}}})

(def sample-activity-dissoc
  {"id"         "http://www.example.com/tincan/activities/multipart"
   "objectType" "Activity"})

(deftest dissoc-statement-properties-nongen-test
  (testing "dissoc non-immutable statement properties"
    (is (= {"id"      sample-id
            "actor"   sample-group-dissoc
            "verb"    sample-verb-dissoc
            "object"  sample-activity-dissoc
            "context" {"instructor" sample-group-dissoc
                       "team"       sample-group-dissoc
                       "contextActivities"
                       {"category" #{sample-activity-dissoc}
                        "parent"   #{sample-activity-dissoc}
                        "grouping" #{sample-activity-dissoc}
                        "other"    #{sample-activity-dissoc}}}}
           (ss/dissoc-statement-properties
            {"id"      sample-id
             "actor"   sample-group
             "verb"    sample-verb
             "object"  sample-activity
             "context" {"instructor" sample-group
                        "team"       sample-group
                        "contextActivities"
                        {"category" [sample-activity
                                     sample-activity]
                         "parent"   [sample-activity
                                     sample-activity]
                         "grouping" [sample-activity
                                     sample-activity]
                         "other"    [sample-activity
                                     sample-activity]}}})))
    (is (= {"id"     sample-id
            "actor"  sample-group-dissoc
            "verb"   sample-verb-dissoc
            "object" {"objectType" "SubStatement"
                      "actor"      sample-group-dissoc
                      "verb"       sample-verb-dissoc
                      "object"     sample-activity-dissoc
                      "context"
                      {"instructor"        sample-group-dissoc
                       "team"              sample-group-dissoc
                       "contextActivities"
                       {"category" #{sample-activity-dissoc}
                        "parent"   #{sample-activity-dissoc}
                        "grouping" #{sample-activity-dissoc}
                        "other"    #{sample-activity-dissoc}}}}}
           (ss/dissoc-statement-properties
            {"id"      sample-id
             "actor"   sample-group
             "verb"    sample-verb
             "object"  {"objectType" "SubStatement"
                        "actor"   sample-group
                        "verb"    sample-verb
                        "object"  sample-activity
                        "context" {"instructor" sample-group
                                   "team"       sample-group
                                   "contextActivities"
                                   {"category" [sample-activity
                                                sample-activity]
                                    "parent"   [sample-activity
                                                sample-activity]
                                    "grouping" [sample-activity
                                                sample-activity]
                                    "other"    [sample-activity
                                                sample-activity]}}}})))))
