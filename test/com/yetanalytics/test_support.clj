(ns com.yetanalytics.test-support
  (:require
   [clojure.test :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as stest]))

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
