(ns com.yetanalytics.lrs.pedestal.routes.about
  (:require [com.yetanalytics.lrs :as lrs]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)]
              (assoc ctx :response (let [{body :body
                                          ?etag :etag}
                                         (lrs/get-about lrs)]
                                     (cond-> {:status 200
                                              :body body}
                                       ?etag (assoc :headers
                                                    {"etag" ?etag}))))))})
