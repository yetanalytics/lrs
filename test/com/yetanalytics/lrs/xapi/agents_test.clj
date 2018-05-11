(ns com.yetanalytics.lrs.xapi.agents-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.xapi.agents :as ag]))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs.xapi.agents
  {:default {::stc/opts {:num-tests 100 :max-size 3}}})
