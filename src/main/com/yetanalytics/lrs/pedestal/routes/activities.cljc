(ns com.yetanalytics.lrs.pedestal.routes.activities
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            [clojure.spec.alpha :as s :include-macros true]))

(s/fdef get-response
  :args (s/cat :ctx map?
               :get-about-ret ::p/get-activity-ret))

(defn get-response
  [ctx {:keys [error activity] ?etag :etag :as _activity-response}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (assoc ctx
           :response
           (if activity
             (cond-> {:status 200 :body activity}
               ?etag (assoc :com.yetanalytics.lrs.pedestal.interceptor/etag
                            ?etag))
             {:status 404}))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn handle-get-fn
     [{auth-identity ::auth/identity
       :keys [xapi com.yetanalytics/lrs] :as ctx}]
     (let [{params :xapi.activities.GET.request/params} xapi]
       (if (p/activity-info-resource-async? lrs)
         (a/go
           (get-response ctx (a/<!
                              (lrs/get-activity-async lrs
                                                      auth-identity
                                                      params))))
         (get-response ctx (lrs/get-activity lrs auth-identity params)))))})
