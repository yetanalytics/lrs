(ns com.yetanalytics.lrs.pedestal.routes.about
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            [clojure.spec.alpha :as s :include-macros true]))

(s/fdef get-response
  :args (s/cat :ctx map?
               :get-about-ret ::p/get-about-ret))

(defn get-response [{:keys [com.yetanalytics/lrs] :as ctx}
                    {error :error
                     body :body
                     ?etag :etag :as lrs-response}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (assoc ctx :response
           (cond-> {:status 200
                    :body body}
             ?etag (assoc :headers
                          {"etag" ?etag})))))

(def handle-get
  {:name ::handle-get
   :enter (fn [{auth-identity ::auth/identity
                :keys [com.yetanalytics/lrs] :as ctx}]
            (if (p/about-resource-async? lrs)
              (a/go (get-response ctx
                                  (a/<! (lrs/get-about-async lrs auth-identity))))
              (get-response ctx (a/<! (lrs/get-about lrs auth-identity)))))})
