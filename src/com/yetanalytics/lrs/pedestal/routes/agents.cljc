(ns com.yetanalytics.lrs.pedestal.routes.agents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]))

(defn get-response [{:keys [com.yetanalytics/lrs] :as ctx}
                    {person :person ?etag :etag :as lrs-response}]
  (cond-> (assoc ctx :response
                 {:status 200
                  :body person})
    ?etag (assoc
           :com.yetanalytics.lrs.pedestal.interceptor/etag
           ?etag)))

(def handle-get
  {:name ::handle-get
   :enter (fn [{auth-identity ::auth/identity
                :keys [com.yetanalytics/lrs
                       xapi] :as ctx}]
            (let [{params :xapi.agents.GET.request/params} xapi]
              (if (p/agent-info-resource-async? lrs)
                (a/go
                  (get-response ctx
                                (a/<!
                                 (lrs/get-person-async lrs auth-identity params))))
                (get-response ctx (lrs/get-person lrs auth-identity params)))))})
