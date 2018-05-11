(ns com.yetanalytics.lrs.xapi.activities-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.xapi.activities :as ac]))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs.xapi.activities
  {:default {::stc/opts {:num-tests 100 :max-size 3}}})
