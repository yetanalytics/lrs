(ns com.yetanalytics.lrs.pedestal.routes.activities
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a :include-macros true]))

(defn get-response [{:keys [com.yetanalytics/lrs] :as ctx}
                    {activity :activity
                     ?etag :etag :as activity-response}]
  (assoc ctx
         :response
         (if activity
           (cond-> {:status 200 :body activity}
             ?etag (assoc :com.yetanalytics.lrs.pedestal.interceptor/etag ?etag))
           {:status 404})))

(def handle-get
  {:name ::handle-get
   :enter (fn [{:keys [xapi
                       com.yetanalytics/lrs] :as ctx}]
            (let [{params :xapi.activities.GET.request/params} xapi]
              (if (p/activity-info-resource-async? lrs)
                (a/go
                  (get-response ctx (a/<! (lrs/get-activity-async lrs params))))
                (get-response ctx (lrs/get-activity lrs params)))))})
