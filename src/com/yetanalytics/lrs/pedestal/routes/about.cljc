(ns com.yetanalytics.lrs.pedestal.routes.about
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a :include-macros true]))

(defn get-response [{:keys [com.yetanalytics/lrs] :as ctx}
                    {body :body
                     ?etag :etag :as lrs-response}]
  (assoc ctx :response
         (cond-> {:status 200
                  :body body}
           ?etag (assoc :headers
                        {"etag" ?etag}))))

(def handle-get
  {:name ::handle-get
   :enter (fn [{:keys [com.yetanalytics/lrs] :as ctx}]
            (if (p/about-resource-async? lrs)
              (a/go (get-response ctx (a/<! (lrs/get-about-async lrs))))
              (get-response ctx (a/<! (lrs/get-about lrs)))))})