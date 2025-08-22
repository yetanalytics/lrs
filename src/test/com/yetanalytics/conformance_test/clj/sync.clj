(ns com.yetanalytics.conformance-test.clj.sync
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.yetanalytics.lrs.test-runner :as runner]
   [mem-lrs.server :as server]
   [io.pedestal.http :as http]))

(use-fixtures :once #(runner/test-suite-fixture % :branch "adding-back-compat"))

(deftest test-lrs-sync
  (testing "clj/java sync 1.0.3"
    (let [s   (server/run-dev :reload-routes? false
                              :mode :sync)
          ret (runner/conformant?
               "-e"
               "http://localhost:8080/xapi"
               "-b"
               "-z"
               "-x" "1.0.3")]
      (http/stop s)
      (is (true? ret))))
  (testing "clj/java sync 2.0.0"
    (let [s   (server/run-dev :reload-routes? false
                              :mode :sync)
          ret (runner/conformant?
               "-e"
               "http://localhost:8080/xapi"
               "-b"
               "-z"
               "-x" "2.0.0")]
      (http/stop s)
      (is (true? ret)))))
