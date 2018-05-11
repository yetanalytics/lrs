(ns com.yetanalytics.lrs.xapi.document-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs.xapi.document
  {:default {::stc/opts {:num-tests 500}}})
