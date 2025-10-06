(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            [clojure.spec.alpha :as s :include-macros true]))

(s/fdef get-response
  :args (s/cat :ctx map?
               :get-about-ret ::p/get-person-ret))

(defn get-response
  [ctx {:keys [error person] ?etag :etag :as _lrs-response}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (cond-> (assoc ctx :response
                   {:status 200 :body person})
      ?etag (assoc
             :com.yetanalytics.lrs.pedestal.interceptor/etag
             ?etag))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn handle-get-fn
     [{auth-identity ::auth/identity
       :keys [com.yetanalytics/lrs xapi] :as ctx}]
     (let [{params :xapi.agents.GET.request/params} xapi]
       (if (p/agent-info-resource-async? lrs)
         (a/go
           (get-response ctx (a/<!
                              (lrs/get-person-async lrs ctx auth-identity params))))
         (get-response ctx (lrs/get-person lrs ctx auth-identity params)))))})
