(ns com.yetanalytics.lrs-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support]
            [mem-lrs.server :as server]
            ))

(use-fixtures :once support/test-suite-fixture)

(deftest lrs-conformance-test
  (let [s (server/run-dev :reload-routes? false)]
    (is (true? (support/run-test-suite)))))
