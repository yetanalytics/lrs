(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  {params :xapi.agents.GET.request/params} (:xapi ctx)]
              (if (p/agent-info-resource-async? lrs)
                (a/go
                  (let [{person :person ?etag :etag} (a/<! (lrs/get-person-async lrs params))]
                    (cond-> (assoc ctx :response
                                   {:status 200
                                    :body person})
                      ?etag (assoc
                             :com.yetanalytics.lrs.pedestal.interceptor/etag
                             ?etag))))
                (let [{person :person ?etag :etag} (lrs/get-person lrs params)]
                  (cond-> (assoc ctx :response
                                 {:status 200
                                  :body person})
                    ?etag (assoc
                           :com.yetanalytics.lrs.pedestal.interceptor/etag
                           ?etag))))))})
