(ns com.yetanalytics.lrs.xapi.statements-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [clojure.spec.test.alpha :as stest]))

(alias 'stc 'clojure.spec.test.check)

(deftest statements-test
  (let [results (stest/check
                 (stest/enumerate-namespace 'com.yetanalytics.lrs.xapi.statements)
                 {::stc/opts {:num-tests 10 :max-size 10}})
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
