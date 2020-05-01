(ns com.yetanalytics.test-runner
  (:require
   clojure.test.check
   clojure.test.check.properties
   [clojure.test :as test
             :refer [run-tests] :include-macros true]
            #?(:clj com.yetanalytics.lrs-test
               :cljs [cljs.nodejs :refer [process]])
            com.yetanalytics.lrs.xapi.activities-test
            com.yetanalytics.lrs.xapi.agents-test
            com.yetanalytics.lrs.xapi.document-test
            com.yetanalytics.lrs.xapi.statements-test
            com.yetanalytics.lrs.xapi.statements.timestamp-test
            com.yetanalytics.lrs.impl.memory-test
            com.yetanalytics.lrs.pedestal.http.multipart-mixed-test
            com.yetanalytics.lrs.auth-test
            ))

(defmethod clojure.test/report #?(:cljs [::test/default :begin-test-ns]
                                  :clj :begin-test-ns)
  [m]
  (let [nn (ns-name (:ns m))]
    (println "\n;;" nn (apply str (repeat (- 76
                                             (count (name nn))) ";")))))

(defmethod clojure.test/report #?(:cljs [::test/default :begin-test-var]
                                  :clj :begin-test-var)
  [m]
  (print "\n  - var" (test/testing-vars-str m) "..."))

(defmethod clojure.test/report #?(:cljs [::test/default :end-test-var]
                                  :clj :end-test-var)
  [m]
  (print " Done!\n"))

#?(:cljs (defmethod clojure.test/report [::test/default :end-run-tests] [m]
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
              'com.yetanalytics.lrs.xapi.statements-test
              'com.yetanalytics.lrs.xapi.statements.timestamp-test
              'com.yetanalytics.lrs.impl.memory-test
              'com.yetanalytics.lrs.pedestal.http.multipart-mixed-test
              'com.yetanalytics.lrs.auth-test)]
    #?(:clj (if (= 0 fail error)
              (System/exit 0)
              (System/exit 1))
       :cljs nil)))

#?(:cljs (set! *main-cli-fn* -main))
