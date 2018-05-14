(ns com.yetanalytics.lrs.pedestal.routes.activities
  (:require [com.yetanalytics.lrs :as lrs]
            [clojure.core.async :as a]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (a/go
              (let [lrs (get ctx :com.yetanalytics/lrs)
                    {params :xapi.activities.GET.request/params} (:xapi ctx)]
                (assoc ctx
                       :response
                       (let [{activity :activity
                              ?etag :etag} (a/<! (lrs/get-activity lrs params))]
                         (if activity
                           (cond-> {:status 200 :body activity}
                             ?etag (assoc :com.yetanalytics.lrs.pedestal.interceptor/etag))
                           {:status 404})
                         )))))})
