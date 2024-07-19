(ns com.yetanalytics.lrs.pedestal.routes
  (:require
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi-i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.document :as doc-i]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as statements-i]
   [com.yetanalytics.lrs.pedestal.routes.about :as about]
   [com.yetanalytics.lrs.pedestal.routes.statements :as statements]
   [com.yetanalytics.lrs.pedestal.routes.agents :as agents]
   [com.yetanalytics.lrs.pedestal.routes.activities :as activities]
   [com.yetanalytics.lrs.pedestal.routes.documents :as documents]
   [com.yetanalytics.lrs.pedestal.interceptor.auth :as auth-i]
   [com.yetanalytics.gen-openapi.core :as gc]
   [com.yetanalytics.lrs.pedestal.openapi :as openapi]
   [io.pedestal.interceptor :refer [interceptor]]
   #?@(:cljs [[goog.string :refer [format]]
              goog.string.format])))

(defn method-not-allowed [_]
  {:status 405})

(defn build-document-routes
  [interceptors & {:keys [path-prefix
                          file-scanner]
                   :or   {path-prefix "/xapi"}}]
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
       (let [spec-kw
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
                 :delete :xapi.agents.profile.DELETE.request/params))
             doc-params-interceptor 
             (xapi-i/params-interceptor spec-kw)             
             params-interceptors
             (cond-> [doc-params-interceptor]
               ;; Scan files if scanner is present on PUT/POST
               (and file-scanner
                    (contains? #{:put :post} method))
               (conj
                (doc-i/scan-document file-scanner)))
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
         (cond-> [path method all-interceptors :route-name route-name]
           (not= method :head)
           (gc/annotate (openapi/spec spec-kw))))))))

(def health
  (interceptor
   {:name ::health
    :enter (fn health-fn [ctx]
             (assoc ctx :response {:status 200 :body ""}))}))

(defn build
  "Given a map with :lrs implementation, builds and returns xAPI routes
  in pedestal table format.

  Optional keys:
    :path-prefix - defines the prefix from root for xAPI routes, default /xapi
    :wrap-interceptors - a vector of interceptors to apply to every route.
      The default vector includes an error interceptor which should be replaced
      if this setting is provided.
    :file-scanner - a function that takes the content of any arbitrary
      user-submitted file and returns nil if it is safe, or a map with :message
      describing why it is unsafe. If unsafe the request will fail with a 400."
  [{:keys [lrs
           path-prefix
           wrap-interceptors
           file-scanner]
    :or {path-prefix "/xapi"
         wrap-interceptors [i/error-interceptor]}}]
  (let [lrs-i                       (i/lrs-interceptor lrs)
        global-interceptors-no-auth (into wrap-interceptors
                                          (conj i/common-interceptors
                                                lrs-i))
        global-interceptors         (into wrap-interceptors
                                          (conj i/common-interceptors
                                                lrs-i
                                                auth-i/lrs-authenticate
                                                auth-i/lrs-authorize))
        protected-interceptors      (into wrap-interceptors
                                          (concat
                                           global-interceptors
                                           i/xapi-protected-interceptors))
        document-interceptors       (into wrap-interceptors
                                          (concat
                                           (conj i/doc-interceptors-base
                                                 lrs-i
                                                 auth-i/lrs-authenticate
                                                 auth-i/lrs-authorize)
                                           i/xapi-protected-interceptors))]
    (into #{;; health check
            (gc/annotate
             ["/health"
              :get (conj global-interceptors-no-auth
                         health)]
             (openapi/annotations :health))

            ;; xapi
            (gc/annotate
             [(format "%s/about" path-prefix)
              :get (conj global-interceptors-no-auth
                         about/handle-get)]
             (openapi/annotations :about))
            [(format "%s/about" path-prefix)
             :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.about/any]

            ;; xapi statements
            (gc/annotate
             [(format "%s/statements" path-prefix)
              :get (into
                    [auth-i/www-authenticate]
                    (concat
                     protected-interceptors
                     [statements-i/set-consistent-through
                      (xapi-i/params-interceptor
                       :xapi.statements.GET.request/params)
                      statements/handle-get]))]
             (openapi/annotations :statements-get))
            [(format "%s/statements" path-prefix)
             :head (conj protected-interceptors
                         statements-i/set-consistent-through
                         (xapi-i/params-interceptor
                          :xapi.statements.GET.request/params)
                         statements/handle-get)
             :route-name :com.yetanalytics.lrs.xapi.statements/head]

            (gc/annotate
             [(format "%s/statements" path-prefix)
              :put (-> protected-interceptors
                       (into [statements-i/set-consistent-through
                              (xapi-i/params-interceptor
                               :xapi.statements.PUT.request/params)
                              statements-i/parse-multiparts
                              statements-i/validate-request-statements])
                       (cond->
                           file-scanner
                           (conj (statements-i/scan-attachments file-scanner)))
                       (conj statements/handle-put))]
             (openapi/annotations :statements-put))

            (gc/annotate
             [(format "%s/statements" path-prefix)
              :post (-> protected-interceptors
                        (into [statements-i/set-consistent-through
                               statements-i/parse-multiparts
                               statements-i/validate-request-statements])
                        (cond->
                            file-scanner
                          (conj (statements-i/scan-attachments file-scanner)))
                        (conj statements/handle-post))]
             (openapi/annotations :statements-post))

            [(format "%s/statements" path-prefix)
             :any method-not-allowed
             :route-name :com.yetanalytics.lrs.xapi.statements/any]

            ;; agents
            (gc/annotate
             [(format "%s/agents" path-prefix)
              :get (conj protected-interceptors
                         (xapi-i/params-interceptor
                          :xapi.agents.GET.request/params)
                         agents/handle-get)]
             (openapi/annotations :agents-post))
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
            (gc/annotate
             [(format "%s/activities" path-prefix)
              :get (conj protected-interceptors
                         (xapi-i/params-interceptor
                          :xapi.activities.GET.request/params)
                         activities/handle-get)]
             (openapi/annotations :activities-post))
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
                                 :path-prefix path-prefix
                                 :file-scanner file-scanner))))
