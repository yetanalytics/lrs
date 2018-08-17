(ns com.yetanalytics.lrs.pedestal.interceptor.auth
  (:require [com.yetanalytics.lrs.auth :as auth]
            [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [io.pedestal.interceptor :refer [interceptor]]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.spec.common :as cs]))

(def lrs-authenticate
  (interceptor
   {:name ::lrs-authenticate
    :enter (->> (fn [{:keys [com.yetanalytics/lrs] :as ctx}]
                  (if (::auth/identity ctx)
                    ctx
                    (if (and (not (::i/force-sync ctx))
                             (p/lrs-auth-async-instance? lrs))
                      (a/go
                        (let [auth-result (a/<! (lrs/authenticate-async lrs ctx))]
                          (if-not (= auth-result ::auth/forbidden)
                            (assoc ctx ::auth/identity auth-result)
                            (assoc (chain/terminate ctx)
                                   :response
                                   {:status 401
                                    :body "FORBIDDEN"}))))
                      (let [auth-result (lrs/authenticate lrs ctx)]
                        (if-not (= auth-result ::auth/forbidden)
                          (assoc ctx ::auth/identity auth-result)
                          (assoc (chain/terminate ctx)
                                 :response
                                 {:status 401
                                  :body "FORBIDDEN"}))))))
                ;; TODO: this forced sync is needed due to the etag interceptor
                ;; being broken. Fix and remove!
                #?(:clj (comp (fn [x]
                                (if (cs/read-port? x)
                                  (a/<!! x)
                                  x)))))}))

(def lrs-authorize
  (interceptor
   {:name ::lrs-authorize
    :enter (->> (fn [{auth-identity ::auth/identity
                      :keys [com.yetanalytics/lrs] :as ctx}]
                  (if (and (not (::i/force-sync ctx))
                           (p/lrs-auth-async-instance? lrs))
                    (a/go
                      (if (a/<! (lrs/authorize-async lrs ctx auth-identity))
                        ctx
                        (assoc (chain/terminate ctx)
                               :response
                               {:status 403
                                :body "UNAUTHORIZED"})))
                    (if (lrs/authorize lrs ctx auth-identity)
                      ctx
                      (assoc (chain/terminate ctx)
                             :response
                             {:status 403
                              :body "UNAUTHORIZED"}))))
                ;; TODO: this forced sync is needed due to the etag interceptor
                ;; being broken. Fix and remove!
                #?(:clj (comp (fn [x]
                                (if (cs/read-port? x)
                                  (a/<!! x)
                                  x)))))}))
