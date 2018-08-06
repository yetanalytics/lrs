(ns com.yetanalytics.conformance-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support]
            [mem-lrs.server :as server]
            [io.pedestal.http :as http]
            com.yetanalytics.lrs.impl.memory-test
            ))

(use-fixtures :once support/test-suite-fixture)

(deftest lrs-conformance-test
  (let [s (server/run-dev :reload-routes? false)]
    (is (true? (try (support/run-test-suite)
                    (finally
                      ;; Wait for a second, there seems to be some kind of race
                      (Thread/sleep 1000)
                      (http/stop s)))))))

(defn -main []
  (let [{:keys [test pass fail error] :as result}
        (run-tests
         'com.yetanalytics.lrs.impl.memory-test
         'com.yetanalytics.conformance-test)]
    (if (= 0 fail error)
      (System/exit 0)
      (System/exit 1))))
