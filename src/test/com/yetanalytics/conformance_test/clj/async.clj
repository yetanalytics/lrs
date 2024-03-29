(ns com.yetanalytics.conformance-test.clj.async
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.yetanalytics.lrs.test-runner :as runner]
   [mem-lrs.server :as server]
   [io.pedestal.http :as http]))

(use-fixtures :once runner/test-suite-fixture)

(deftest test-lrs-async
  (testing "clj/java async"
    (let [s   (server/run-dev :reload-routes? false
                              :mode :async)
          ret (runner/conformant?
               "-e"
               "http://localhost:8080/xapi"
               "-b"
               "-z"
               "-x" "1.0.3")]
      (http/stop s)
      (is (true? ret)))))
