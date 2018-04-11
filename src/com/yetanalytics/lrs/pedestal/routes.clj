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
                                     i/xapi-protected-interceptors)
        statements-get-params-coercers
        {:limit (fn ^Long [^String limit-str]
                  (Long/parseLong limit-str))
         :page (fn ^Long [^String page-str]
                 (Long/parseLong page-str))
         :attachments (fn ^Boolean [^String s]
                        (Boolean/parseBoolean s))
         :related_activities (fn ^Boolean [^String s]
                               (Boolean/parseBoolean s))
         :related_agents (fn ^Boolean [^String s]
                           (Boolean/parseBoolean s))
         }]
    #{;; xapi
      ["/xapi/about" :get (conj global-interceptors
                                about/handle-get)]
      #_["/xapi/about" :any method-not-allowed
       :route-name :com.yetanalytics.lrs.xapi.about/any]

      ;; xapi statements
      ["/xapi/statements" :get (conj protected-interceptors
                                     (xapi-i/params-interceptor
                                      :xapi.statements.GET.request/params
                                      statements-get-params-coercers)
                                     statements-i/set-consistent-through
                                     statements/handle-get)
       ]
      ["/xapi/statements" :head (conj protected-interceptors
                                      (xapi-i/params-interceptor
                                       :xapi.statements.GET.request/params
                                       statements-get-params-coercers)
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

      }))

(comment
  (map Boolean/parseBoolean ["true"])
  (boolean "false")
  (io.pedestal.http.route/expand-routes (build {:lrs {}}))

  )
