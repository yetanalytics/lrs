(ns com.yetanalytics.test-support
  (:require
   [clojure.test :refer [deftest testing is] :include-macros true]
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.spec.test.alpha :as stest :include-macros true]
   [clojure.pprint :refer [pprint]]
   [clojure.walk :as w]
   [clojure.string :as cs]
   clojure.test.check
   #?@(:clj [[clojure.java.shell :as shell :refer [sh]]
             [clojure.java.io :as io]
             [io.pedestal.log :as log]
             [cheshire.core :as json]])))

#?(:clj (alias 'stc 'clojure.spec.test.check))

(def stc-ret #?(:clj :clojure.spec.test.check/ret
                :cljs :clojure.test.check/ret))

(def stc-opts #?(:clj :clojure.spec.test.check/opts
                 :cljs :clojure.test.check/opts))

(defn failures [check-results]
  (mapv
   (fn [{:keys [spec clojure.spec.test.check/ret sym failure] :as x}]
     [sym
      (-> x
          (update :spec s/describe)
          (dissoc :sym)
          ;; Dissoc the top level trace, leave the shrunken one
          (update stc-ret dissoc :result-data))])
   (remove #(-> %
                stc-ret
                :result
                true?)
           check-results
           #_(apply concat
                  (for [[sym opts] syms-opts]
                    (stest/check sym (or opts {})))))))

#?(:clj (defmacro deftest-check-ns
          "Check all instrumented symbols in an ns. A map of overrides
   can provide options & a default:
   {`foo {::stc/ret {:num-tests 100}}
    :default {::stc/ret {:num-tests 500}}}"
          [test-sym ns-sym & [overrides]]
          (let [default-opts (get overrides :default {})
                qualified-overrides (into {}
                                          (map
                                           (fn [[fn-sym fn-opts]]
                                             [(symbol (name ns-sym)
                                                      (name fn-sym))
                                              fn-opts])
                                           (or (dissoc overrides :default) {})))
                syms-opts (into {}
                                (map (fn [fn-sym]
                                       [fn-sym
                                        (get qualified-overrides fn-sym default-opts)]))
                                (stest/enumerate-namespace ns-sym))]
            `(deftest ~test-sym
               ~@(for [[sym opts] syms-opts]
                   `(testing ~(name sym)
                      (is
                       ~(list 'empty?
                              `(failures (stest/check (quote ~sym) ~opts))))))))))

;; Run the lrs-conformance-test-suite
;; TODO: this should all be its own ns

#?(:clj (defn report-sh-result
          "Print a generic sh output."
          [{:keys [exit out err]}]
          (when-let [o (not-empty out)]
            (.write ^java.io.Writer *out* ^String o)
            (flush))
          (when-let [e (not-empty err)]
            (.write ^java.io.Writer *err* ^String e)
            (flush))))

#?(:clj (defn ensure-test-suite
          "Ensure that the test suite exists and is runnable."
          []
          ;; Attempt Clone
          (log/info :msg "Ensuring LRS Tests...")
          #_(let [{clone-exit :exit :as clone-result} (sh "git" "clone" "https://github.com/adlnet/lrs-conformance-test-suite.git")]
            (report-sh-result clone-result)
            ;; Whatever we do, let's pull master
            (log/info :msg "Getting latest master...")
            (report-sh-result
             (sh "git" "pull" "origin" "master"
                 :dir "lrs-conformance-test-suite"))
            ;; if it's a new clone, we need to install
            (or (when (= clone-exit 0)
                  (log/info :msg "Cloned tests from master!")
                  (log/info :msg "Ensuring nvm and Installing tests...")
                  (let [{install-exit :exit :as node-result}
                        (sh
                         ;; Install NVM
                         "bash" "script/with_node.bash"
                         ;; Install the tests
                         "npm" "install" "--prefix" "lrs-conformance-test-suite")]
                    (report-sh-result node-result)
                    (when (= install-exit 0)
                      (log/info :msg "Tests Ready!")
                      true)))
                false))))

#?(:clj (defn destroy-test-suite
          "Completely remove the tests."
          []
          (log/info :msg "Deleting test suite...")
          #_(report-sh-result (sh "rm" "-rf" "lrs-conformance-test-suite"))))

#?(:clj (defrecord RequestLog [out-str]))

#?(:clj (defn wrap-request-logs
          [log-root]
          (w/prewalk (fn [node]
                       (if (some-> node :log string?)
                         (update node :log #(RequestLog. %))
                         node))
                     log-root)))

#?(:clj (defmethod clojure.pprint/simple-dispatch RequestLog
          [{:keys [out-str]}]
          (clojure.pprint/pprint-logical-block
           :prefix "<" :suffix ">"
           (doseq [line (cs/split-lines out-str)]
             (clojure.pprint/pprint-newline :linear)
             (.write ^java.io.Writer *out* ^String line))
           (clojure.pprint/pprint-newline :linear))))


#?(:clj (defn print-logs []
          (doall
           (doseq [^java.io.File f (rest (file-seq (io/file "lrs-conformance-test-suite/logs")))]
             (printf "\nLog: %s\n\n" (.getPath f))
             (let [{:keys [log] :as test-output} (with-open [rdr (io/reader f)]
                                                   (json/parse-stream rdr keyword))]
               (pprint (wrap-request-logs test-output)))))
          (flush)))

#?(:clj (defn delete-logs []
          (log/info :msg "Cleaning Logs...")
          (doseq [^java.io.File f (rest (file-seq (io/file "lrs-conformance-test-suite/logs")))]
            (log/info :msg (format "Deleting Log: %s"
                                   (.getPath f)))
            (.delete f))))

#?(:clj (defn run-test-suite
          "Run the lrs tests. Returns true on a pass, false on not"
          [& args]
          ;; delete any logs
          (log/info :msg "Running Tests...")
          (let [{:keys [exit out err] :as test-result}
                (apply sh
                       ;; "bash" "../script/with_node.bash"
                       "node" "bin/console_runner.js" "-e" "http://localhost:8080/xapi" "-b" "-z"
                       (concat
                        args
                        [:dir "lrs-conformance-test-suite"]))]
            (report-sh-result test-result)
            (print-logs)
            (if (= 0 exit)
              true
              false))))

#?(:clj (defn test-suite-fixture
          "Fixture to ensure clean test environment."
          [f]
          (ensure-test-suite)
          (delete-logs)
          (f)))

;; Some spec utils
