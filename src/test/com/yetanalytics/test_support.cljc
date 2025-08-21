(ns com.yetanalytics.test-support
  (:require
   [clojure.core.async :as a :include-macros true]
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.test :refer [deftest testing is #?(:cljs async)] :include-macros true]
   [clojure.test.check]
   #?@(:clj [[clojure.spec.test.alpha :as stest :include-macros true]])
   [io.pedestal.http :as http]
   [com.yetanalytics.lrs.impl.memory :as mem]
   [com.yetanalytics.lrs.pedestal.routes :as r]
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   #?(:cljs [com.yetanalytics.node-chain-provider :as provider])))

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

;; a la https://stackoverflow.com/a/30781278/3532563
(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj
     (a/<!! ch)
     :cljs
     (async done
            (a/take! ch (fn [_] (done))))))

(defn test-server
  "Create (but do not start) an in-memory LRS for testing purposes."
  [& {:keys [port
             lrs-mode
             route-opts]
      :or   {port       8080
             lrs-mode   :sync
             route-opts {}}}]
  (let [lrs     (mem/new-lrs {})
        service {:env                     :dev
                 ::lrs                    lrs
                 ::http/routes            (r/build
                                           (merge
                                            {:lrs lrs
                                             :wrap-interceptors
                                             [i/error-interceptor]}
                                            route-opts))
                 #?@(:clj [::http/resource-path "/public"])
                 ::http/host              "0.0.0.0"
                 ::http/port              port
                 ::http/container-options {:h2c? true
                                           :h2?  false
                                           :ssl? false}
                 ::http/allowed-origins   {:creds           true
                                           :allowed-origins (constantly true)}
                 ::http/type              #?(:clj :jetty
                                             :cljs provider/macchiato-server-fn)
                 ::http/join?             false}]
    (-> service
        i/xapi-default-interceptors
        http/create-server)))
