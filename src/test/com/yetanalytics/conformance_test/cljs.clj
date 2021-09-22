(ns com.yetanalytics.conformance-test.cljs
  (:require
   [clojure.test :refer :all]
   [com.yetanalytics.lrs.test-runner :as runner]
   [io.pedestal.http :as http]
   [clojure.java.shell :as shell :refer [sh]]))

;; Call cljs lrs via shell
(defn- parse-pid
  "get the pid from out or nil"
  [out]
  (re-find #"\d+" out))

(defn- lrs-up?
  []
  (try
    (= (slurp "http://localhost:8080/xapi/about")
       "{\"version\":[\"1.0.0\",\"1.0.1\",\"1.0.2\",\"1.0.3\",\"2.0.0\"]}")
    (catch java.net.ConnectException _
      false)))

(defn- cljs-lrs
  "Starts a cljs LRS and returns a function to stop it."
  []
  (let [{:keys [exit out err] :as ret} (sh "bin/run_cljs_bg.sh")]
    (if-let [pid (and (zero? exit)
                      (parse-pid out))]
      ;; poll until start
      (loop [attempts 10]
        (if (< 0 attempts)
          (if (lrs-up?)
            (fn stop []
              (let [{:keys [exit out err]} (sh "kill" "-9" pid)]
                (when-not (zero? exit)
                  (throw (ex-info "Couldn't stop cljs lrs"
                                  {:type ::cljs-lrs-stop-error})))))
            (do
              (Thread/sleep 500)
              (recur (dec attempts))))
          (throw (ex-info "Couldn't reach LRS"
                          {:type ::cljs-lrs-about-error}))))

      (throw (ex-info "Couldn't start cljs lrs"
                      {:type ::cljs-lrs-start-error
                       :ret ret})))))

(use-fixtures :once #(runner/test-suite-fixture % :branch "LRS-2.0"))

(deftest test-cljs-lrs
  (testing "cljs/javascript async"
    (let [stop-fn (cljs-lrs)
          ret (runner/conformant?
               "-e"
               "http://localhost:8080/xapi"
               "-b"
               "-z")]
      (stop-fn)
      (is (true? ret)))))
