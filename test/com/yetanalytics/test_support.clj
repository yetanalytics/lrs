(ns com.yetanalytics.test-support
  (:require
   [clojure.test :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as stest]
   [clojure.java.shell :as shell :refer [sh]]
   [clojure.java.io :as io]
   [io.pedestal.log :as log]
   [cheshire.core :as json]
   [clojure.pprint :refer [pprint]]
   [clojure.walk :as w]
   [clojure.string :as cs]))

(alias 'stc 'clojure.spec.test.check)

(defn failures [syms-opts]
  (mapv
   (fn [{:keys [spec stc/ret sym failure] :as x}]
     [sym
      (-> x
          (update :spec s/describe)
          (dissoc :sym)
          ;; Dissoc the top level trace, leave the shrunken one
          (update ::stc/ret dissoc :result-data))])
   (remove #(-> %
                ::stc/ret
                :result
                true?)
           (apply concat
                  (for [[sym opts] syms-opts]
                    (stest/check sym (or opts {})))))))

(defmacro deftest-check-ns
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
                      `(failures {(quote ~sym) ~opts}))))))))

;; Run the lrs-conformance-test-suite

(defn ensure-test-suite
  "Ensure that the test suite exists and is runnable."
  []
  ;; Attempt Clone
  (log/info :msg "Ensuring LRS Tests...")
  (let [{clone-exit :exit} (sh "git" "clone" "https://github.com/adlnet/lrs-conformance-test-suite.git")]
    ;; Whatever we do, let's pull master
    (sh "git" "pull" "origin" "master"
        :dir "lrs-conformance-test-suite")
    ;; if it's a new clone, we need to install
    (or (when (= clone-exit 0)
          (log/info :msg "Cloned tests from master")
          (let [{install-exit :exit} (sh
                                      ;; Install node via nvm
                                      "bash" "script/ensure_nvm.bash"
                                      "&&"
                                      ;; Install the tests
                                      "npm" "install" :dir "lrs-conformance-test-suite")]
            (when (= install-exit 0)
              (log/info :msg "Installed npm stuff")
              true)))
        false)))

(defn destroy-test-suite
  "Completely remove the tests."
  []
  (log/info :msg "Deleting test suite...")
  (sh "rm" "-rf" "lrs-conformance-test-suite"))

(defrecord RequestLog [out-str])

(defn wrap-request-logs
  [log-root]
  (w/prewalk (fn [node]
               (if (some-> node :log string?)
                 (update node :log #(RequestLog. %))
                 node))
             log-root))

(defmethod clojure.pprint/simple-dispatch RequestLog
  [{:keys [out-str]}]
  (clojure.pprint/pprint-logical-block
   :prefix "<" :suffix ">"
   (doseq [line (cs/split-lines out-str)]
     (clojure.pprint/pprint-newline :linear)
     (.write ^java.io.Writer *out* line))
   (clojure.pprint/pprint-newline :linear)))


(defn print-logs []
  (doall
   (doseq [^java.io.File f (rest (file-seq (io/file "lrs-conformance-test-suite/logs")))]
     (printf "\nLog: %s\n\n" (.getPath f))
     (let [{:keys [log] :as test-output} (with-open [rdr (io/reader f)]
                                           (json/parse-stream rdr keyword))]
       (pprint (wrap-request-logs test-output)))))
  (flush))

(defn delete-logs []
  (log/info :msg "Cleaning Logs...")
  (doseq [^java.io.File f (rest (file-seq (io/file "lrs-conformance-test-suite/logs")))]
    (log/info :msg (format "Deleting Log: %s"
                           (.getPath f)))
    (.delete f)))

(defn run-test-suite
  "Run the lrs tests. Returns true on a pass, false on not"
  [& args]
  ;; delete any logs
  (log/info :msg "Running Tests...")
  (let [{:keys [exit out err]}
        (apply sh "node" "bin/console_runner.js" "-e" "http://localhost:8080/xapi" "-b" "-z"
               (concat
                args
                [:dir "lrs-conformance-test-suite"
                 :out-enc :bytes]))]
    (io/copy out *out*)
    (flush)
    (when-let [e (not-empty err)]
      (io/copy e *err*))
    (flush)
    (print-logs)
    (if (= 0 exit)
      true
      false)))

(defn test-suite-fixture
  "Fixture to ensure clean test environment."
  [f]
  (ensure-test-suite)
  (delete-logs)
  (f))
