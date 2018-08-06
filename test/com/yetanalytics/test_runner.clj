(ns com.yetanalytics.test-runner
  (:require [clojure.test :refer :all]
            com.yetanalytics.lrs-test
            com.yetanalytics.lrs.xapi.activities-test
            com.yetanalytics.lrs.xapi.agents-test
            com.yetanalytics.lrs.xapi.document-test
            com.yetanalytics.lrs.xapi.statements-test
            ))

(deftest bad-test (is false))

(defn -main []
  (let [{:keys [test pass fail error] :as result}
        (run-tests
         'com.yetanalytics.test-runner
         'com.yetanalytics.lrs-test
         'com.yetanalytics.lrs.xapi.activities-test
         'com.yetanalytics.lrs.xapi.agents-test
         'com.yetanalytics.lrs.xapi.document-test
         'com.yetanalytics.lrs.xapi.statements-test)]
    (if (= 0 fail error)
      (System/exit 0)
      (System/exit 1))))
