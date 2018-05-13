(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs :as lrs]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  {params :xapi.agents.GET.request/params} (:xapi ctx)
                  {person :person ?etag :etag} (lrs/get-person lrs params)]
              (cond-> (assoc ctx :response
                             {:status 200
                              :body person})
                ?etag (assoc
                       :com.yetanalytics.lrs.pedestal.interceptor/etag
                       ?etag))))})
