(ns com.yetanalytics.conformance-test
  (:require
   [clojure.test :refer [run-tests]]
   com.yetanalytics.conformance-test.clj.sync
   com.yetanalytics.conformance-test.clj.async
   com.yetanalytics.conformance-test.cljs))

(defn -main [& [mode]]
  (let [mode (or mode "clj-sync")
        {:keys [test pass fail error] :as result}
        (run-tests
         (case mode
           "clj-sync"  'com.yetanalytics.conformance-test.clj.sync
           "clj-async" 'com.yetanalytics.conformance-test.clj.async
           "cljs"      'com.yetanalytics.conformance-test.cljs))]
    (if (= 0 fail error)
      (System/exit 0)
      (System/exit 1))))
