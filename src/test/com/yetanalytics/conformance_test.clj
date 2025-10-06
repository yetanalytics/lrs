(ns com.yetanalytics.conformance-test
  (:require
   [clojure.test :refer [run-tests]]
   com.yetanalytics.conformance-test.clj.sync
   com.yetanalytics.conformance-test.clj.async
   com.yetanalytics.conformance-test.cljs
   [xapi-schema.spec :as xs :include-macros true]))

(defn -main [& [mode]]
  (let [mode (or mode "clj-sync")
        {:keys [_test _pass fail error] :as _result}
        ;; Set the more permissive 2.0.0 version of the specs
        ;; this should force instrumentation to use 2.0.0
        (binding [xs/*xapi-version* "2.0.0"]
          (run-tests
           (case mode
             "clj-sync"  'com.yetanalytics.conformance-test.clj.sync
             "clj-async" 'com.yetanalytics.conformance-test.clj.async
             "cljs"      'com.yetanalytics.conformance-test.cljs)))]
    (if (= 0 fail error)
      (System/exit 0)
      (System/exit 1))))
