(ns com.yetanalytics.test-runner
  (:require [#?(:clj clojure.test
                :cljs cljs.test) :refer [run-tests] :include-macros true]
            #?(:clj com.yetanalytics.lrs-test
               :cljs [cljs.nodejs :refer [process]])
            com.yetanalytics.lrs.xapi.activities-test
            com.yetanalytics.lrs.xapi.agents-test
            com.yetanalytics.lrs.xapi.document-test
            com.yetanalytics.lrs.xapi.statements-test
            ))

#?(:cljs (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
           (.exit process
                  (if (cljs.test/successful? m)
                    0
                    1))))

(defn ^:export -main []
  (when-let [{:keys [test pass fail error] :as result}
             (run-tests
              #?(:clj 'com.yetanalytics.lrs-test)
              'com.yetanalytics.lrs.xapi.activities-test
              'com.yetanalytics.lrs.xapi.agents-test
              'com.yetanalytics.lrs.xapi.document-test
              'com.yetanalytics.lrs.xapi.statements-test)]
    #?(:clj (if (= 0 fail error)
              (System/exit 0)
              (System/exit 1))
       :cljs nil)))

#?(:cljs (set! *main-cli-fn* -main))
