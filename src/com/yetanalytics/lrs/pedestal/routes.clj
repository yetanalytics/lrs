(ns com.yetanalytics.lrs.pedestal.routes
  (:require
   [io.pedestal.http :as http]
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi-i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as statements-i]
   [com.yetanalytics.lrs.pedestal.routes.about :as about]
   [com.yetanalytics.lrs.pedestal.routes.statements :as statements]))

(defn method-not-allowed [_]
  {:status 405})

(defn build [{:keys [lrs]}]
  (let [global-interceptors (conj i/common-interceptors
                                  (i/lrs-interceptor lrs))
        protected-interceptors (into global-interceptors
                                     i/xapi-protected-interceptors)]
    #{;; xapi
      ["/xapi/about" :get (conj global-interceptors
                                about/handle-get)]
      #_["/xapi/about" :any method-not-allowed
       :route-name :com.yetanalytics.lrs.xapi.about/any]

      ;; xapi statements
      ["/xapi/statements" :get (conj protected-interceptors
                                     (xapi-i/params-interceptor
                                      :xapi.statements.GET.request/params
                                      {:limit (fn ^Long [^String limit-str]
                                                (Long/parseLong limit-str))
                                       :page (fn ^Long [^String page-str]
                                               (Long/parseLong page-str))})
                                     statements-i/set-consistent-through
                                     statements/handle-get)
       ]
      #_["/xapi/statements" :head (into xapi-protected-interceptors ;; TODO: see if pedestal handles head
                                      )
       :route-name :nave.xapi.statements/head]
      ["/xapi/statements" :put (conj protected-interceptors
                                     (xapi-i/params-interceptor
                                      :xapi.statements.PUT.request/params)
                                     statements-i/parse-multiparts
                                     statements-i/validate-request-statements
                                     statements-i/set-consistent-through
                                     statements/handle-put)
       ]
      ["/xapi/statements" :post (conj protected-interceptors
                                      statements-i/parse-multiparts
                                      statements-i/validate-request-statements
                                      statements-i/set-consistent-through
                                      statements/handle-post)]
      ["/xapi/statements" :any method-not-allowed
       :route-name :com.yetanalytics.lrs.xapi.statements/any]

      }))

(comment

  (io.pedestal.http.route/expand-routes (build {:lrs {}}))

  )
