(ns com.yetanalytics.lrs.impl.memory-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.impl.memory :refer :all]
            ))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs.impl.memory
  {:default {::stc/opts {:num-tests 10 :max-size 2}}})
