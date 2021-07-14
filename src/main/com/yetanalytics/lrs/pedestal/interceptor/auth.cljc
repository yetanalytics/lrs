(ns com.yetanalytics.lrs.pedestal.interceptor.auth
  (:require [com.yetanalytics.lrs.auth :as auth]
            [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [io.pedestal.interceptor :refer [interceptor]]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.spec.common :as cs]))

(defn handle-authenticate
  "Logic for handling authentication results, common sync + async"
  [ctx {:keys [error
               result]}]
  (if error
    (assoc ctx ::chain/error error)
    (if-not (or (= result ::auth/unauthorized)
                ;; old incorrect keyword accepted for backwards compat
                ;; TODO: remove!
                (= result ::auth/forbidden))
      (assoc ctx ::auth/identity result)
      (assoc (chain/terminate ctx)
             :response
             {:status 401
              :body "UNAUTHORIZED"}))))

(def lrs-authenticate
  (interceptor
   {:name ::lrs-authenticate
    :enter (fn [{:keys [com.yetanalytics/lrs] :as ctx}]
                  (if (::auth/identity ctx)
                    ctx
                    (if (p/lrs-auth-async-instance? lrs)
                      (a/go
                        (handle-authenticate ctx (a/<! (lrs/authenticate-async lrs ctx))))
                      (handle-authenticate ctx
                                           (lrs/authenticate lrs ctx)))))}))

(defn handle-authorize
  "Logic for handling authorization results, common sync + async"
  [ctx {:keys [error
               result]}]
  (if error
    (assoc ctx ::chain/error error)
    (if result
      ctx
      (assoc (chain/terminate ctx)
             :response
             {:status 403
              :body "FORBIDDEN"}))))

(def lrs-authorize
  (interceptor
   {:name ::lrs-authorize
    :enter (fn [{auth-identity ::auth/identity
                 :keys [com.yetanalytics/lrs] :as ctx}]
             (if (p/lrs-auth-async-instance? lrs)
               (a/go
                 (handle-authorize ctx (a/<! (lrs/authorize-async lrs ctx auth-identity))))
               (handle-authorize ctx (lrs/authorize lrs ctx auth-identity))))}))
