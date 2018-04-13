(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs :as lrs]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  {params :xapi.agents.GET.request/params} (:xapi ctx)]
              (assoc ctx :response
                     {:status 200
                      :body (lrs/get-person lrs params)})))})
