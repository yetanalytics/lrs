(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs :as lrs]
            [clojure.core.async :as a]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (a/go
              (let [lrs (get ctx :com.yetanalytics/lrs)
                    {params :xapi.agents.GET.request/params} (:xapi ctx)
                    {person :person ?etag :etag} (a/<! (lrs/get-person lrs params))]
                (cond-> (assoc ctx :response
                               {:status 200
                                :body person})
                  ?etag (assoc
                         :com.yetanalytics.lrs.pedestal.interceptor/etag
                         ?etag)))))})
