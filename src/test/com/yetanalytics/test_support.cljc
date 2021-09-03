(ns com.yetanalytics.test-support
  ;; clj-kondo incorrectly marks ns used in macros
  #_{:clj-kondo/ignore [:unused-namespace :unused-referred-var]}
  (:require
   [clojure.test :refer [deftest testing is] :include-macros true]
   [clojure.spec.test.alpha :as stest :include-macros true]
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.test.check]))

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
           check-results)))

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
