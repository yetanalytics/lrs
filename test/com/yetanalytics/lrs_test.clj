(ns com.yetanalytics.lrs-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs :as lrs]
            ))

(alias 'stc 'clojure.spec.test.check)

#_(deftest-check-ns ns-test com.yetanalytics.lrs)
