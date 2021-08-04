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

(def stc-ret :clojure.spec.test.check/ret)

(def stc-opts :clojure.spec.test.check/opts)

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
