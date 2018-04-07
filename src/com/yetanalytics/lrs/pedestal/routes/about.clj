(ns com.yetanalytics.lrs.pedestal.routes.about
  (:require [com.yetanalytics.lrs.protocol.xapi.about :as about-proto]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)]
              (assoc ctx :response {:status 200
                                    :body (about-proto/get-about lrs)})))})
