(ns com.yetanalytics.lrs.pedestal.routes.about
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)]
              (if (p/about-resource-async? lrs)
                (a/go (assoc ctx :response (let [{body :body
                                                  ?etag :etag}
                                                 (a/<! (lrs/get-about-async lrs))]
                                             (cond-> {:status 200
                                                      :body body}
                                               ?etag (assoc :headers
                                                            {"etag" ?etag}))))))
              (assoc ctx :response (let [{body :body
                                          ?etag :etag}
                                         (lrs/get-about lrs)]
                                     (cond-> {:status 200
                                              :body body}
                                       ?etag (assoc :headers
                                                    {"etag" ?etag}))))))})
