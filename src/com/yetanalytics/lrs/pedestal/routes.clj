(ns com.yetanalytics.lrs.pedestal.routes
  (:require
   [io.pedestal.http :as http]
   [com.yetanalytics.lrs.protocol :as p]
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi-i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as statements-i]
   [com.yetanalytics.lrs.pedestal.routes.about :as about]
   [com.yetanalytics.lrs.pedestal.routes.statements :as statements]
   [com.yetanalytics.lrs.pedestal.routes.agents :as agents]
   [com.yetanalytics.lrs.pedestal.routes.activities :as activities]
   [com.yetanalytics.lrs.pedestal.routes.documents :as documents]))

(defn method-not-allowed [_]
  {:status 405})


(defn document-routes [interceptors
                       path-fragment
                       route-name-ns]
  (let [path (str "/xapi/" path-fragment)]
    [[path :put (conj interceptors
                      (xapi-i/params-interceptor
                       ::p/set-document-params)
                      documents/handle-put)
      :route-name (keyword route-name-ns "put")]
     [path :post (conj interceptors
                       (xapi-i/params-interceptor
                        ::p/set-document-params)
                       documents/handle-post)
      :route-name (keyword route-name-ns "post")]

     [path :get (conj interceptors
                      (xapi-i/params-interceptor
                       ::p/get-document-all-params)
                      documents/handle-get)
      :route-name (keyword route-name-ns "get")]
     [path :head (conj interceptors
                       (xapi-i/params-interceptor
                        ::p/get-document-all-params)
                       documents/handle-get)
      :route-name (keyword route-name-ns "head")]
     [path :delete (conj interceptors
                         (xapi-i/params-interceptor
                          ::p/delete-document-all-params)
                         documents/handle-delete)
      :route-name (keyword route-name-ns "delete")]
     [path :any method-not-allowed
      :route-name (keyword route-name-ns "any")]]))

(defn build [{:keys [lrs]}]
  (let [global-interceptors (conj i/common-interceptors
                                  (i/lrs-interceptor lrs))
        protected-interceptors (into global-interceptors
                                     i/xapi-protected-interceptors)
        document-interceptors (into (conj i/doc-interceptors-base
                                          (i/lrs-interceptor lrs))
                                    i/xapi-protected-interceptors)]
    (into #{;; xapi
            ["/xapi/about" :get (conj global-interceptors
                                      about/handle-get)]
            ["/xapi/about" :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.about/any]

            ;; xapi statements
            ["/xapi/statements" :get (conj protected-interceptors
                                           statements-i/set-consistent-through
                                           (xapi-i/params-interceptor
                                            :xapi.statements.GET.request/params)
                                           statements/handle-get)]
            ["/xapi/statements" :head (conj protected-interceptors
                                            statements-i/set-consistent-through
                                            (xapi-i/params-interceptor
                                             :xapi.statements.GET.request/params)
                                            statements/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.statements/head]
            ["/xapi/statements" :put (conj protected-interceptors
                                           statements-i/set-consistent-through
                                           (xapi-i/params-interceptor
                                            :xapi.statements.PUT.request/params)
                                           statements-i/parse-multiparts
                                           statements-i/validate-request-statements
                                           statements/handle-put)
             ]
            ["/xapi/statements" :post (conj protected-interceptors
                                            statements-i/set-consistent-through
                                            statements-i/parse-multiparts
                                            statements-i/validate-request-statements
                                            statements/handle-post)]
            ["/xapi/statements" :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.statements/any]

            ["/xapi/agents" :get (conj protected-interceptors
                                       (xapi-i/params-interceptor
                                        :xapi.agents.GET.request/params)
                                       agents/handle-get)]
            ["/xapi/agents" :head (conj protected-interceptors
                                        (xapi-i/params-interceptor
                                         :xapi.agents.GET.request/params)
                                        agents/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.agents/head]
            ["/xapi/agents" :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.agents/any]

            ["/xapi/activities" :get (conj protected-interceptors
                                           (xapi-i/params-interceptor
                                            :xapi.activities.GET.request/params)
                                           activities/handle-get)]
            ["/xapi/activities" :head (conj protected-interceptors
                                            (xapi-i/params-interceptor
                                             :xapi.activities.GET.request/params)
                                            activities/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.activities/head]
            ["/xapi/activities" :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.activities/any]}
          (concat
           (document-routes document-interceptors
                            "activities/state"
                            "com.yetanalytics.lrs.xapi.activities.state")
           (document-routes document-interceptors
                            "activities/profile"
                            "com.yetanalytics.lrs.xapi.activities.profile")
           (document-routes document-interceptors
                            "agents/profile"
                            "com.yetanalytics.lrs.xapi.agents.profile")))))

(comment
  (map Boolean/parseBoolean ["true"])
  (boolean "false")
  (io.pedestal.http.route/expand-routes (build {:lrs {}}))

  )
