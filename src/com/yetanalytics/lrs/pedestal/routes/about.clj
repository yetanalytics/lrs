(ns com.yetanalytics.lrs.pedestal.routes.about
  (:require [com.yetanalytics.lrs :as lrs]
            [clojure.core.async :as a]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (a/go
              (let [lrs (get ctx :com.yetanalytics/lrs)]
                (assoc ctx :response (let [{body :body
                                            ?etag :etag}
                                           (a/<! (lrs/get-about lrs))]
                                       (cond-> {:status 200
                                                :body body}
                                         ?etag (assoc :headers
                                                      {"etag" ?etag})))))))})
