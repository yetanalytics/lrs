(ns com.yetanalytics.lrs.pedestal.routes
  (:require
   [com.yetanalytics.lrs.protocol :as p]
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi-i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as statements-i]
   [com.yetanalytics.lrs.pedestal.routes.about :as about]
   [com.yetanalytics.lrs.pedestal.routes.statements :as statements]
   [com.yetanalytics.lrs.pedestal.routes.agents :as agents]
   [com.yetanalytics.lrs.pedestal.routes.activities :as activities]
   [com.yetanalytics.lrs.pedestal.routes.documents :as documents]
   [com.yetanalytics.lrs.pedestal.interceptor.auth :as auth-i]
   [io.pedestal.interceptor :refer [interceptor]]
   #?@(:cljs [[goog.string :refer [format]]
              goog.string.format])))

(defn method-not-allowed [_]
  {:status 405})

(defn build-document-routes [interceptors]
  (into []
        (for [[resource doc-type :as resource-tuple] [["activities" "state"]
                                                      ["activities" "profile"]
                                                      ["agents"     "profile"]]
              :let [path (format "/xapi/%s/%s" resource doc-type)
                    route-name-ns (format "com.yetanalytics.lrs.xapi.%s.%s"
                                          resource doc-type)]
              method [:put :post :get :head :delete :any]

              ]
          [path method
           (if (= method :any)
             method-not-allowed
             (conj (into interceptors
                         [(xapi-i/params-interceptor
                           (case resource-tuple
                             ["activities" "state"]
                             (case method
                               :put    :xapi.activities.state.PUT.request/params
                               :post   :xapi.activities.state.POST.request/params
                               :get    :xapi.activities.state.GET.request/params
                               :head   :xapi.activities.state.GET.request/params
                               :delete :xapi.activities.state.DELETE.request/params)
                             ["activities" "profile"]
                             (case method
                               :put    :xapi.activities.profile.PUT.request/params
                               :post   :xapi.activities.profile.POST.request/params
                               :get    :xapi.activities.profile.GET.request/params
                               :head   :xapi.activities.profile.GET.request/params
                               :delete :xapi.activities.profile.DELETE.request/params)
                             ["agents"     "profile"]
                             (case method
                               :put    :xapi.agents.profile.PUT.request/params
                               :post   :xapi.agents.profile.POST.request/params
                               :get    :xapi.agents.profile.GET.request/params
                               :head   :xapi.agents.profile.GET.request/params
                               :delete :xapi.agents.profile.DELETE.request/params)))
                          ])
                   (case method
                     :put documents/handle-put
                     :post documents/handle-post
                     :get documents/handle-get
                     :head documents/handle-get
                     :delete documents/handle-delete)))
           :route-name (keyword route-name-ns (name method))])))

(def health
  (interceptor
   {:name ::health
    :enter (fn [ctx]
             (assoc ctx :response {:status 200 :body ""}))}))

(defn build [{:keys [lrs]}]
  (let [lrs-i (i/lrs-interceptor lrs)
        global-interceptors-no-auth
        (conj i/common-interceptors
              lrs-i)
        global-interceptors (conj i/common-interceptors
                                  lrs-i
                                  auth-i/lrs-authenticate
                                  auth-i/lrs-authorize
                                  )
        protected-interceptors (into global-interceptors
                                     i/xapi-protected-interceptors)
        document-interceptors (into (conj i/doc-interceptors-base
                                          lrs-i
                                          auth-i/lrs-authenticate
                                          auth-i/lrs-authorize
                                          )
                                    i/xapi-protected-interceptors)]
    (into #{["/health" :get (conj global-interceptors-no-auth
                                  health)]
            ;; xapi
            ["/xapi/about" :get (conj global-interceptors-no-auth
                                      about/handle-get)]
            ["/xapi/about" :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.about/any]

            ;; xapi statements
            ["/xapi/statements" :get (into [(auth-i/www-authenticate
                                             "Statement Viewer")]
                                           (concat
                                            protected-interceptors
                                            [statements-i/set-consistent-through
                                             (xapi-i/params-interceptor
                                              :xapi.statements.GET.request/params)
                                             statements/handle-get]))]
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
          (build-document-routes document-interceptors))))
