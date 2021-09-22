(ns com.yetanalytics.test-support
  (:require
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.test.check]
   #?@(:clj [[clojure.test :refer [deftest testing is] :include-macros true]
             [clojure.spec.test.alpha :as stest :include-macros true]])))

#?(:clj (alias 'stc 'clojure.spec.test.check))

(def stc-ret :clojure.spec.test.check/ret)

(def stc-opts :clojure.spec.test.check/opts)

(defn failures [check-results]
  (mapv
   (fn [{:keys [sym] :as x}]
     [sym (-> x
              (update :spec s/describe)
              (dissoc :sym)
              ;; Dissoc the top level trace, leave the shrunken one
              (update stc-ret dissoc :result-data))])
   (remove #(-> %
                stc-ret
                :result
                true?)
           check-results)))

#?(:clj
   (defmacro deftest-check-ns
     "Check all instrumented symbols in an ns. A map of overrides
      can provide options & a default:
      {`foo     {::stc/ret {:num-tests 100}}
       :default {::stc/ret {:num-tests 500}}}"
     [test-sym ns-sym & [overrides]]
     (let [default-opts# (get overrides :default {})
           overrides#    (into {} ; Qualified overrides
                               (map
                                (fn [[fn-sym fn-opts]]
                                  [(symbol (name ns-sym) (name fn-sym))
                                   fn-opts])
                                (or (dissoc overrides :default) {})))
           syms-opts#    (into {}
                               (map (fn [fn-sym]
                                      [fn-sym
                                       (get overrides# fn-sym default-opts#)]))
                               (stest/enumerate-namespace ns-sym))]
       `(deftest ~test-sym
          ~@(for [[sym opts] syms-opts#]
              `(testing ~(name sym)
                 (is
                  ~(list 'empty?
                         `(failures (stest/check (quote ~sym) ~opts))))))))))
