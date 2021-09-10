(ns com.yetanalytics.lrs.pedestal.routes
  (:require
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

(defn build-document-routes
  [interceptors & {:keys [path-prefix] :or {path-prefix "/xapi"}}]
  ;; Build all possible doc routes by looping over each pair of
  ;; resource + doc type and HTTP method
  (into
   []
   (for [[resource doc-type :as resource-tuple] [["activities" "state"]
                                                 ["activities" "profile"]
                                                 ["agents"     "profile"]]
         :let [path          (format "%s/%s/%s"
                                     path-prefix
                                     resource
                                     doc-type)
               route-name-ns (format "com.yetanalytics.lrs.xapi.%s.%s"
                                     resource
                                     doc-type)]
         method [:put :post :get :head :delete :any]]
     (if (= :any method)
       [path
        method
        method-not-allowed
        :route-name (keyword route-name-ns (name method))]
       (let [params-interceptors
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
                   :delete :xapi.agents.profile.DELETE.request/params)))]
             method-interceptor
             (case method
               :put    documents/handle-put
               :post   documents/handle-post
               :get    documents/handle-get
               :head   documents/handle-get
               :delete documents/handle-delete)
             all-interceptors
             (conj (into interceptors params-interceptors)
                   method-interceptor)
             route-name
             (keyword route-name-ns (name method))]
         [path
          method
          all-interceptors
          :route-name route-name])))))

(def health
  (interceptor
   {:name ::health
    :enter (fn health-fn [ctx]
             (assoc ctx :response {:status 200 :body ""}))}))

(defn build-routes [{:keys [lrs path-prefix]
                     :or {path-prefix "/xapi"}}]
  (let [lrs-i                       (i/lrs-interceptor lrs)
        global-interceptors-no-auth (conj i/common-interceptors
                                          lrs-i)
        global-interceptors         (conj i/common-interceptors
                                          lrs-i
                                          auth-i/lrs-authenticate
                                          auth-i/lrs-authorize)
        protected-interceptors      (into global-interceptors
                                          i/xapi-protected-interceptors)
        document-interceptors       (into (conj i/doc-interceptors-base
                                                lrs-i
                                                auth-i/lrs-authenticate
                                                auth-i/lrs-authorize)
                                          i/xapi-protected-interceptors)]
    (into #{;; health check
            ["/health"
             :get (conj global-interceptors-no-auth
                        health)]

            ;; xapi
            [(format "%s/about" path-prefix)
             :get (conj global-interceptors-no-auth
                        about/handle-get)]
            [(format "%s/about" path-prefix)
             :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.about/any]

            ;; xapi statements
            [(format "%s/statements" path-prefix)
             :get (into [auth-i/www-authenticate]
                        (concat
                         protected-interceptors
                         [statements-i/set-consistent-through
                          (xapi-i/params-interceptor
                           :xapi.statements.GET.request/params)
                          statements/handle-get]))]
            [(format "%s/statements" path-prefix)
             :head (conj protected-interceptors
                         statements-i/set-consistent-through
                         (xapi-i/params-interceptor
                          :xapi.statements.GET.request/params)
                         statements/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.statements/head]
            [(format "%s/statements" path-prefix)
             :put (conj protected-interceptors
                        statements-i/set-consistent-through
                        (xapi-i/params-interceptor
                         :xapi.statements.PUT.request/params)
                        statements-i/parse-multiparts
                        statements-i/validate-request-statements
                        statements/handle-put)]
            [(format "%s/statements" path-prefix)
             :post (conj protected-interceptors
                         statements-i/set-consistent-through
                         statements-i/parse-multiparts
                         statements-i/validate-request-statements
                         statements/handle-post)]
            [(format "%s/statements" path-prefix)
             :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.statements/any]

            ;; agents
            [(format "%s/agents" path-prefix)
             :get (conj protected-interceptors
                        (xapi-i/params-interceptor
                         :xapi.agents.GET.request/params)
                        agents/handle-get)]
            [(format "%s/agents" path-prefix)
             :head (conj protected-interceptors
                         (xapi-i/params-interceptor
                          :xapi.agents.GET.request/params)
                         agents/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.agents/head]
            [(format "%s/agents" path-prefix)
             :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.agents/any]

            ;; activities
            [(format "%s/activities" path-prefix)
             :get (conj protected-interceptors
                        (xapi-i/params-interceptor
                         :xapi.activities.GET.request/params)
                        activities/handle-get)]
            [(format "%s/activities" path-prefix)
             :head (conj protected-interceptors
                         (xapi-i/params-interceptor
                          :xapi.activities.GET.request/params)
                         activities/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.activities/head]
            [(format "%s/activities" path-prefix)
             :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.activities/any]}

          ;; documents
          (build-document-routes document-interceptors
                                 :path-prefix path-prefix))))
