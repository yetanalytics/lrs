(ns com.yetanalytics.lrs-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support :refer [deftest-check-ns]]
            com.yetanalytics.lrs.impl.memory
            [com.yetanalytics.lrs :refer :all]
            ))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs
  {:default {::stc/opts {:num-tests 50 :max-size 3}}})
