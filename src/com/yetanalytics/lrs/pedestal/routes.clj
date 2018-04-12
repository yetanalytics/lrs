(ns com.yetanalytics.lrs.pedestal.routes
  (:require
   [io.pedestal.http :as http]
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi-i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as statements-i]
   [com.yetanalytics.lrs.pedestal.routes.about :as about]
   [com.yetanalytics.lrs.pedestal.routes.statements :as statements]
   [com.yetanalytics.lrs.pedestal.routes.agents :as agents]
   [com.yetanalytics.lrs.pedestal.routes.activities :as activities]))

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
      ["/xapi/about" :any method-not-allowed
       :route-name :com.yetanalytics.lrs.xapi.about/any]

      ;; xapi statements
      ["/xapi/statements" :get (conj protected-interceptors
                                     (xapi-i/params-interceptor
                                      :xapi.statements.GET.request/params)
                                     statements-i/set-consistent-through
                                     statements/handle-get)]
      ["/xapi/statements" :head (conj protected-interceptors
                                      (xapi-i/params-interceptor
                                       :xapi.statements.GET.request/params)
                                      statements-i/set-consistent-through
                                      statements/handle-get)
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
      ["/xapi/agents" :get (conj protected-interceptors
                                 (xapi-i/params-interceptor
                                  :xapi.agents.GET.request/params)
                                 agents/handle-get)]
      ["/xapi/agents" :any method-not-allowed
       :route-name :com.yetanalytics.lrs.xapi.agents/any]
      ["/xapi/activities" :get (conj protected-interceptors
                                     (xapi-i/params-interceptor
                                      :xapi.activities.GET.request/params)
                                     activities/handle-get)]
      ["/xapi/activities" :any method-not-allowed
       :route-name :com.yetanalytics.lrs.xapi.activities/any]

      }))

(comment
  (map Boolean/parseBoolean ["true"])
  (boolean "false")
  (io.pedestal.http.route/expand-routes (build {:lrs {}}))

  )
