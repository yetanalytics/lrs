(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.string :as cstr]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xi]
            [xapi-schema.spec.resources :as xsr]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            #?(:clj [cheshire.core :as json])
            [clojure.spec.alpha :as s :include-macros true]
            [#?(:clj io.pedestal.log
                :cljs com.yetanalytics.lrs.util.log) :as log]))

(defn find-some [m & kws]
  (some (partial find m)
        kws))

(s/fdef get-single-response
  :args (s/cat :ctx map?
               :get-document-ret ::p/get-document-ret))

(defn get-single-response
  [{:keys [xapi
           request
           com.yetanalytics/lrs] :as ctx}
   {error :error
    ?document :document
    ?etag :etag :as lrs-response}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error
           error)
    (if-let [{:keys [content-type
                     content-length
                     contents
                     id
                     updated] :as result} ?document]
      (assoc ctx :response {:status 200
                            :headers (cond-> {"Content-Type" content-type
                                              "Content-Length" (str content-length)
                                              "Last-Modified" updated}
                                       ?etag (assoc "etag" ?etag))
                            :body contents #_(ByteBuffer/wrap ^bytes contents) #_(ByteArrayOutputStream. ^bytes contents)})
      (assoc ctx :response {:status 404}))))

(s/fdef get-multiple-response
  :args (s/cat :ctx map?
               :get-document-ids-ret ::p/get-document-ids-ret))

(defn get-multiple-response
  [{:keys [xapi
           request
           com.yetanalytics/lrs] :as ctx}
   {error :error
    ids :document-ids
    ?etag :etag :as lrs-response}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error
           error)
    (cond->
        (assoc ctx :response {:headers {"Content-Type" "application/json"}
                              :status 200
                              :body (-> (into [] ids)
                                        #?@(:clj [json/generate-string]
                                            :cljs [clj->js
                                                   (->> (.stringify js/JSON))])
                                        )})
      ?etag (assoc ::i/etag ?etag))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn [{auth-identity ::auth/identity
         :keys [xapi
                request
                com.yetanalytics/lrs] :as ctx}]
     (let [[params-spec [params-type params]]
           (find-some xapi
                      :xapi.activities.state.GET.request/params
                      :xapi.activities.profile.GET.request/params
                      :xapi.agents.profile.GET.request/params)]
       (if (p/document-resource-async? lrs)
         (a/go
           (case params-type
             :id (get-single-response ctx (a/<! (lrs/get-document-async
                                                 lrs auth-identity params)))
             :query (get-multiple-response ctx
                                           (a/<!
                                            (lrs/get-document-ids-async
                                             lrs auth-identity params)))
             (assoc ctx :response {:status 400})))
         (case params-type
           :id (get-single-response ctx (lrs/get-document lrs auth-identity params))
           :query (get-multiple-response ctx (lrs/get-document-ids lrs
                                                                   auth-identity
                                                                   params))
           (assoc ctx :response {:status 400})))))
   :leave i/etag-leave})

(defn- get-params-enter-fn
  "Given the current xapi map, return the params processing for a GET"
  [xapi]
  (:enter
   (xi/params-interceptor
    (case (first (find-some xapi
                            :xapi.activities.state.PUT.request/params
                            :xapi.activities.state.POST.request/params
                            :xapi.activities.state.DELETE.request/params

                            :xapi.activities.profile.PUT.request/params
                            :xapi.activities.profile.POST.request/params
                            :xapi.activities.profile.DELETE.request/params

                            :xapi.agents.profile.PUT.request/params
                            :xapi.agents.profile.POST.request/params
                            :xapi.agents.profile.DELETE.request/params))

    :xapi.activities.state.PUT.request/params :xapi.activities.state.GET.request/params
    :xapi.activities.state.POST.request/params :xapi.activities.state.GET.request/params
    :xapi.activities.state.DELETE.request/params :xapi.activities.state.GET.request/params

    :xapi.activities.profile.PUT.request/params :xapi.activities.profile.GET.request/params
    :xapi.activities.profile.POST.request/params :xapi.activities.profile.GET.request/params
    :xapi.activities.profile.DELETE.request/params :xapi.activities.profile.GET.request/params

    :xapi.agents.profile.PUT.request/params :xapi.agents.profile.GET.request/params
    :xapi.agents.profile.POST.request/params :xapi.agents.profile.GET.request/params
    :xapi.agents.profile.DELETE.request/params :xapi.agents.profile.GET.request/params))))

(defn etags-preproc
  "Process if-match rules and etags for the handler. Will call `handle-get` to check doc state."
  [enter-fn]
  (fn wrap-enter
    [{auth-identity ::auth/identity
      :keys [xapi
             request
             com.yetanalytics/lrs] :as ctx}]
    (let [{:keys [body content-type content-length headers]} request
          {:strs [if-match if-none-match]} headers]
      (if (= nil if-match if-none-match)
        ;; If no headers provided, go ahead
        (enter-fn ctx)
        (let [;; TODO: Params overhaul, very silly rn
              get-params-enter (get-params-enter-fn
                                xapi)
              {get-enter :enter
               get-leave :leave} handle-get]
          (if (p/document-resource-async? lrs)
            (a/go
              (let [get-ctx (-> ctx
                                (update :request dissoc :body)
                                (assoc-in [:request
                                           :request-method]
                                          :get)
                                get-params-enter
                                get-enter
                                a/<!
                                get-leave)
                    if-match-ok? (case if-match
                                   nil true
                                   "*" (= 200
                                          (get-in get-ctx
                                                  [:response
                                                   :status]))
                                   (contains? (i/etag-header->etag-set if-match)
                                              (::i/etag get-ctx)))

                    if-none-match-ok? (case if-none-match
                                        nil true
                                        "*" (= 404
                                               (get-in get-ctx
                                                       [:response
                                                        :status]))
                                        (not (contains? (i/etag-header->etag-set if-none-match)
                                                        (::i/etag get-ctx))))]
                (if (and if-match-ok? if-none-match-ok?)
                  (a/<! (enter-fn ctx))
                  (assoc ctx :response
                         (let [{{:keys [status] :as get-response} :response} get-ctx]
                           (if (= 400 status)
                             get-response
                             {:status 412}))))))
            (let [get-ctx (-> ctx
                              (update :request dissoc :body)
                              (assoc-in [:request
                                         :request-method]
                                        :get)
                              get-params-enter
                              get-enter
                              get-leave)
                  if-match-ok? (case if-match
                                 nil true
                                 "*" (= 200
                                        (get-in get-ctx
                                                [:response
                                                 :status]))
                                 (contains? (i/etag-header->etag-set if-match)
                                            (::i/etag get-ctx)))

                  if-none-match-ok? (case if-none-match
                                      nil true
                                      "*" (= 404
                                             (get-in get-ctx
                                                     [:response
                                                      :status]))
                                      (not (contains? (i/etag-header->etag-set if-none-match)
                                                      (::i/etag get-ctx))))]
              (if (and if-match-ok? if-none-match-ok?)
                (enter-fn ctx)
                (assoc ctx :response
                       (let [{{:keys [status] :as get-response} :response} get-ctx]
                         (if (= 400 status)
                           get-response
                           {:status 412})))))))))))

(s/fdef put-response
  :args (s/cat :ctx map?
               :set-document-ret ::p/set-document-ret))

(defn put-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [error]}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (assoc ctx :response {:status 204})))


;; TODO: better handling of sync/async, dedupe
(def handle-put
  {:name ::handle-put
   :enter (etags-preproc
           (fn [{auth-identity ::auth/identity
                 :keys [xapi
                        request
                        com.yetanalytics/lrs] :as ctx}]
             (if (p/document-resource-async? lrs)
               (a/go
                 (try (let [{:keys [body content-type content-length headers]} request]
                        (if-let [[_ params] (find xapi :xapi.activities.state.PUT.request/params)]
                          (put-response ctx (a/<! (lrs/set-document-async
                                                   lrs auth-identity params
                                                   {:content-type content-type
                                                    :content-length content-length
                                                    :contents body}
                                                   false)))
                          (let [[params-spec params] (find-some xapi
                                                                :xapi.activities.profile.PUT.request/params
                                                                :xapi.agents.profile.PUT.request/params)
                                {:strs [if-match if-none-match]} headers]
                            (if (or if-match if-none-match)
                              (put-response ctx (a/<! (lrs/set-document-async
                                                       lrs auth-identity params
                                                       {:content-type content-type
                                                        :content-length content-length
                                                        :contents body}
                                                       false)))
                              ;; if neither header is present
                              (let [{:keys [error
                                            document]} (a/<! (lrs/get-document-async
                                                              lrs auth-identity params))]
                                (cond
                                  error
                                  (assoc ctx :io.pedestal.interceptor.chain/error error)
                                  document
                                  (assoc ctx :response {:status 409
                                                        :headers {"Content-Type" "text/plain"}
                                                        :body "If-Match or If-None-Match header is required for existing document."})
                                  :else (assoc ctx :response {:status 400})))))))
                      (catch #?(:clj Exception :cljs js/Error) ex
                        (assoc ctx :io.pedestal.interceptor.chain/error ex))))
               (try (let [{:keys [body content-type content-length headers]} request]
                      (if-let [[_ params] (find xapi :xapi.activities.state.PUT.request/params)]
                        (put-response ctx (lrs/set-document
                                           lrs auth-identity params
                                           {:content-type content-type
                                            :content-length content-length
                                            :contents body}
                                           false))
                        (let [[params-spec params] (find-some xapi
                                                              :xapi.activities.profile.PUT.request/params
                                                              :xapi.agents.profile.PUT.request/params)
                              {:strs [if-match if-none-match]} headers]
                          (if (or if-match if-none-match)
                            (put-response ctx
                                          (lrs/set-document
                                           lrs auth-identity params
                                           {:content-type content-type
                                            :content-length content-length
                                            :contents body}
                                           false))
                            ;; if neither header is present
                            (let [{:keys [error
                                          document]} (lrs/get-document
                                                      lrs auth-identity params)]
                              (cond
                                error
                                (assoc ctx :io.pedestal.interceptor.chain/error error)
                                document
                                (assoc ctx :response {:status 409
                                                      :headers {"Content-Type" "text/plain"}
                                                      :body "If-Match or If-None-Match header is required for existing document."})
                                :else (assoc ctx :response {:status 400})))))))
                    (catch #?(:clj Exception :cljs js/Error) ex
                      (assoc ctx :io.pedestal.interceptor.chain/error ex))))))})

(s/fdef post-response
  :args (s/cat :ctx map?
               :set-document-ret ::p/set-document-ret))

(defn post-response [{:keys [xapi
                             request
                             com.yetanalytics/lrs] :as ctx}
                     {:keys [error] :as lrs-response}]
  (if error
    (let [exd (ex-data error)]
      (if (#{:com.yetanalytics.lrs.xapi.document/json-read-error
             :com.yetanalytics.lrs.xapi.document/json-not-object-error
             :com.yetanalytics.lrs.xapi.document/invalid-merge}
           (:type exd))
        (assoc ctx :response {:status 400})
        (assoc ctx :io.pedestal.interceptor.chain/error
               error)))
    (assoc ctx :response {:status 204})))

(def handle-post
  {:name ::handle-post
   :enter (etags-preproc
           (fn [{auth-identity ::auth/identity
                 :keys [xapi
                        request
                        com.yetanalytics/lrs] :as ctx}]
             (let [[params-spec params] (find-some xapi
                                                   :xapi.activities.state.POST.request/params
                                                   :xapi.activities.profile.POST.request/params
                                                   :xapi.agents.profile.POST.request/params)
                   {:keys [body content-type content-length]} request]
               (if (p/document-resource-async? lrs)
                 (a/go
                   (post-response ctx (a/<! (lrs/set-document-async
                                             lrs auth-identity params
                                             {:content-type content-type
                                              :content-length content-length
                                              :contents body}
                                             true))))
                 (post-response ctx (lrs/set-document
                                     lrs auth-identity params
                                     {:content-type content-type
                                      :content-length content-length
                                      :contents body}
                                     true))))))})



(s/fdef delete-response
  :args (s/cat :ctx map?
               :delete-document-ret
               (s/or :single ::p/delete-document-ret
                     :multiple ::p/delete-documents-ret)))

(defn delete-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [error]}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (assoc ctx :response {:status 204})))

(def handle-delete
  {:name ::handle-delete
   :enter (etags-preproc
           (fn [{auth-identity ::auth/identity
                 :keys [xapi
                        request
                        com.yetanalytics/lrs] :as ctx}]
             (if (p/document-resource-async? lrs)
               (a/go
                 (delete-response ctx
                                  (if-let [[params-spec params] (find-some xapi
                                                                           :xapi.activities.profile.DELETE.request/params
                                                                           :xapi.agents.profile.DELETE.request/params)]
                                    (a/<! (lrs/delete-document-async lrs auth-identity params))
                                    (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                                      (case params-type
                                        :id (a/<! (lrs/delete-document-async
                                                   lrs auth-identity params))
                                        :context (a/<! (lrs/delete-documents-async
                                                        lrs auth-identity params)))))))
               (delete-response ctx
                                (if-let [[params-spec params] (find-some xapi
                                                                         :xapi.activities.profile.DELETE.request/params
                                                                         :xapi.agents.profile.DELETE.request/params)]
                                  (lrs/delete-document lrs auth-identity params)
                                  (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                                    (case params-type
                                      :id (lrs/delete-document lrs auth-identity params)
                                      :context (lrs/delete-documents lrs auth-identity params))))))))})
