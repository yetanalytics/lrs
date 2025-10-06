(ns com.yetanalytics.conformance-test.clj.async
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.yetanalytics.lrs.test-runner :as runner]
   [mem-lrs.server :as server]
   [io.pedestal.http :as http]))

(use-fixtures :once runner/test-suite-fixture)

(deftest test-lrs-async
  (testing "clj/java async 1.0.3"
    (let [s   (server/run-dev :reload-routes? false
                              :mode :async)
          ret (runner/conformant?
               "-e"
               "http://localhost:8080/xapi"
               "-b"
               "-z"
               "-x" "1.0.3")]
      (http/stop s)
      (is (true? ret))))
  (testing "clj/java async 2.0.0"
    (let [s   (server/run-dev :reload-routes? false
                              :mode :async)
          ret (runner/conformant?
               "-e"
               "http://localhost:8080/xapi"
               "-b"
               "-z"
               "-x" "2.0.0")]
      (http/stop s)
      (is (true? ret)))))
