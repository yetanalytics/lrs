(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs.protocol.xapi.agents :as agent-proto]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  params (get-in ctx [:request :params] {})]
              (assoc ctx :response
                     (try {:status 200
                           :body (agent-proto/get-person lrs params)}
                          (catch clojure.lang.ExceptionInfo exi
                            (let [exd (ex-data exi)]
                              (case (:type exd)
                                ::agent-proto/invalid-params
                                {:status 400
                                 :body {:error {:message (.getMessage exi)
                                                :params (:params exd)}}}
                                (throw exi))))))))})
