(ns com.yetanalytics.lrs.pedestal.routes.activities
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  {params :xapi.activities.GET.request/params} (:xapi ctx)]
              (if (p/activity-info-resource-async? lrs)
                (a/go
                  (assoc ctx
                         :response
                         (let [{activity :activity
                                ?etag :etag} (a/<! (lrs/get-activity-async lrs params))]
                           (if activity
                             (cond-> {:status 200 :body activity}
                               ?etag (assoc :com.yetanalytics.lrs.pedestal.interceptor/etag))
                             {:status 404})
                           )))
                (assoc ctx
                       :response
                       (let [{activity :activity
                              ?etag :etag} (lrs/get-activity lrs params)]
                         (if activity
                           (cond-> {:status 200 :body activity}
                             ?etag (assoc :com.yetanalytics.lrs.pedestal.interceptor/etag))
                           {:status 404})
                         )))))})
