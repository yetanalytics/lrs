(ns com.yetanalytics.lrs.pedestal.routes.activities
  (:require [com.yetanalytics.lrs.protocol.xapi.activities :as activities-proto]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  {params :xapi.activities.GET.request/params} (:xapi ctx)]
              (assoc ctx
                     :response
                     (if-let [activity (activities-proto/get-activity lrs params)]
                       {:status 200 :body activity}
                       {:status 404}))))})
