(ns com.yetanalytics.conformance-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.lrs.test-runner :as runner]
            [mem-lrs.server :as server]
            [io.pedestal.http :as http]
            com.yetanalytics.lrs.impl.memory-test))

(use-fixtures :once runner/test-suite-fixture)

(deftest lrs-conformance-test
  (doseq [mode [:sync :async]]
    (testing (name mode)
      (let [s (server/run-dev :reload-routes? false
                              :mode mode)
            ret (runner/conformant?
                 "-e"
                 "http://localhost:8080/xapi"
                 "-b"
                 "-z")]
        (http/stop s)
        (is (true? ret))))))

(defn -main []
  (let [{:keys [test pass fail error] :as result}
        (run-tests
         'com.yetanalytics.lrs.impl.memory-test
         'com.yetanalytics.conformance-test)]
    (if (= 0 fail error)
      (System/exit 0)
      (System/exit 1))))
