(ns com.yetanalytics.lrs.routes.activities
  (:require [com.yetanalytics.lrs.protocol.xapi.activities :as activities-proto]))

(def handle-get
  {:name ::handle-get
   :enter (fn [ctx]
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  params (get-in ctx [:request :params] {})]
              (assoc ctx :response
                     (try (if-let [activity (activities-proto/get-activity lrs params)]
                            {:status 200 :body activity}
                            {:status 404})
                          (catch clojure.lang.ExceptionInfo exi
                            (let [exd (ex-data exi)]
                              (case (:type exd)
                                ::activities-proto/invalid-params
                                {:status 400
                                 :body
                                 {:error
                                  {:message (.getMessage exi)
                                   :params (:params exd)}}}
                                (throw exi))))))))})
