(ns com.yetanalytics.lrs.pedestal.interceptor.chain
  (:require [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a]))

(defn prepare-for-async
  "Call all of the :enter-async functions in a context. The purpose of these
  functions is to ready backing servlets or any other machinery for preparing
  an asynchronous response."
  [{:keys [enter-async] :as context}]
  (doseq [enter-async-fn enter-async]
    (enter-async-fn context)))

(defn go-async!!
  "When presented with a channel as the return value of an enter function,
  wait for the channel to return a new-context (via a go block). When a new
  context is received, restart execution of the interceptor chain with that
  context.

  This function is *blocking*"
  ([old-context context-channel]
   (prepare-for-async old-context)
   (if-let [new-context (a/<!! context-channel)]
     (chain/execute new-context)
     (chain/execute (assoc (dissoc old-context ::chain/queue ::chain/async-info)
                           ::chain/stack (get-in old-context [::chain/async-info :stack])
                           ::chain/error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                                  {:execution-id (::chain/execution-id old-context)
                                                   :stage (get-in old-context [::chain/async-info :stage])
                                                   :interceptor (name (get-in old-context [::chain/async-info :interceptor]))
                                                   :exception-type :PedestalChainAsyncPrematureClose})))))
  ([old-context context-channel interceptor-key]
   (prepare-for-async old-context)
   (if-let [new-context (a/<!! context-channel)]
     (chain/execute-only new-context interceptor-key)
     (chain/execute-only (assoc (dissoc old-context ::chain/queue ::chain/async-info)
                                ::chain/stack (get-in old-context [::chain/async-info :stack])
                                ::chain/error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                                       {:execution-id (::chain/execution-id old-context)
                                                        :stage (get-in old-context [::chain/async-info :stage])
                                                        :interceptor (name (get-in old-context [::chain/async-info :interceptor]))
                                                        :exception-type :PedestalChainAsyncPrematureClose}))
                         interceptor-key))))

(defn execute
  [& args]
  (with-redefs [io.pedestal.interceptor.chain/go-async go-async!!]
    (apply chain/execute args)))

(defn execute-only
  [& args]
  (with-redefs [io.pedestal.interceptor.chain/go-async go-async!!]
    (apply chain/execute-only args)))
