(ns com.yetanalytics.lrs.xapi.activities-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.lrs.xapi.activities :as ac]
            [clojure.spec.test.alpha :as stest]))

(alias 'stc 'clojure.spec.test.check)

(deftest activities-test
  (let [results (stest/check
                 (stest/enumerate-namespace 'com.yetanalytics.lrs.xapi.activities)
                 {::stc/opts {:num-tests 100 :max-size 3}})
        failures
        (keep (fn [result]
                (when (false? (:failure result))
                  (-> result
                      (update ::stc/ret dissoc
                              :result-data
                              :fail)
                      (dissoc :spec))))
              results)]
    (is (empty? failures))))
