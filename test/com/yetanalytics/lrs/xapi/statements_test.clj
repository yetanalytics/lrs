(ns com.yetanalytics.lrs.xapi.statements-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            ))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs.xapi.statements
  {:default {::stc/opts {:num-tests 10 :max-size 10}}})
