(ns com.yetanalytics.lrs-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support]
            [mem-lrs.server :as server]
            [io.pedestal.http :as http]
            ))

(use-fixtures :once support/test-suite-fixture)

(deftest lrs-conformance-test
  (let [s (server/run-dev :reload-routes? false)]
    (is (true? (try (support/run-test-suite)
                    (finally
                      ;; Wait for a second, there seems to be some kind of race
                      (Thread/sleep 1000)
                      (http/stop s)))))))
