(ns com.yetanalytics.lrs.pedestal.interceptor.auth
  (:require [com.yetanalytics.lrs.auth :as auth]
            [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a :include-macros true]
            #?@(:cljs [[goog.string :refer [format]]
                       goog.string.format])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authenticate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-authenticate
  "Logic for handling authentication results, common sync + async"
  [ctx {:keys [error result]}]
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
              :body   "UNAUTHORIZED"}))))

(def lrs-authenticate
  (interceptor
   {:name ::lrs-authenticate
    :enter
    (fn authenticate [{:keys [com.yetanalytics/lrs] :as ctx}]
      (if (::auth/identity ctx)
        ctx
        (if (p/lrs-auth-async-instance? lrs)
          (a/go
            (handle-authenticate ctx (a/<! (lrs/authenticate-async lrs ctx))))
          (handle-authenticate ctx (lrs/authenticate lrs ctx)))))}))

;; When used, will direct users to attempt basic auth
;; in the given realm
(def www-authenticate
  (interceptor
   {:name ::www-authenticate
    :leave
    (fn www-authenticate [ctx]
      (if (and (si/accept-html? ctx)
               (some-> ctx
                       :response
                       :status
                       (= 401)))
        (assoc-in ctx
                  [:response
                   :headers
                   "WWW-Authenticate"]
                  (format "Basic realm=\"%s\""
                          (::i/www-auth-realm ctx "LRS")))
        ctx))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authorize
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-authorize
  "Logic for handling authorization results, common sync + async"
  [ctx {:keys [error result]}]
  (if error
    (assoc ctx ::chain/error error)
    (if result
      ctx
      (assoc (chain/terminate ctx)
             :response
             {:status 403
              :body   "FORBIDDEN"}))))

(def lrs-authorize
  (interceptor
   {:name ::lrs-authorize
    :enter
    (fn authorize
      [{auth-identity ::auth/identity
        :keys [com.yetanalytics/lrs] :as ctx}]
      (if (p/lrs-auth-async-instance? lrs)
        (a/go
          (handle-authorize ctx (a/<! (lrs/authorize-async lrs
                                                           ctx
                                                           auth-identity))))
        (handle-authorize ctx (lrs/authorize lrs
                                             ctx
                                             auth-identity))))}))
