(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.auth :as auth]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xi]
            [clojure.spec.alpha :as s :include-macros true]
            [clojure.core.async :as a :include-macros true]
            #?(:clj [cheshire.core :as json])))

(defn find-some [m & kws]
  (some (partial find m)
        kws))

(s/fdef get-single-response
  :args (s/cat :ctx map?
               :get-document-ret ::p/get-document-ret))

(defn get-single-response
  [ctx
   {error     :error
    ?document :document
    ?etag     :etag
    :as       _lrs-response}]
  (if error
    (assoc ctx
           :io.pedestal.interceptor.chain/error
           error)
    (if-let [{:keys [content-type
                     content-length
                     contents
                     _id
                     updated] :as _result} ?document]
      (let [headers (cond-> {"Content-Type"   content-type
                             "Content-Length" (str content-length)
                             "Last-Modified"  updated}
                      ?etag (assoc "etag" ?etag))]
        (assoc ctx :response {:status  200
                              :headers headers
                              :body    contents}))
      (assoc ctx :response {:status 404}))))

(s/fdef get-multiple-response
  :args (s/cat :ctx map?
               :get-document-ids-ret ::p/get-document-ids-ret))

(defn get-multiple-response
  [ctx
   {error :error
    ids   :document-ids
    ?etag :etag
    :as   _lrs-response}]
  (if error
    (assoc ctx
           :io.pedestal.interceptor.chain/error
           error)
    (let [body (-> (into [] ids)
                   #?@(:clj [json/generate-string]
                       :cljs [clj->js
                              (->> (.stringify js/JSON))]))]
      (cond->
       (assoc ctx :response {:status  200
                             :headers {"Content-Type" "application/json"}
                             :body    body})
        ?etag (assoc ::i/etag ?etag)))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn handle-get-fn
     [{auth-identity ::auth/identity
       :keys [xapi
              com.yetanalytics/lrs] :as ctx}]
     (let [[_params-spec [params-type params]]
           (find-some xapi
                      :xapi.activities.state.GET.request/params
                      :xapi.activities.profile.GET.request/params
                      :xapi.agents.profile.GET.request/params)]
       (if (p/document-resource-async? lrs)
         (a/go
           (case params-type
             :id    (get-single-response ctx
                                         (a/<! (lrs/get-document-async
                                                lrs
                                                auth-identity
                                                params)))
             :query (get-multiple-response ctx
                                           (a/<! (lrs/get-document-ids-async
                                                  lrs
                                                  auth-identity
                                                  params)))
             (assoc ctx :response {:status 400})))
         (case params-type
           :id    (get-single-response ctx
                                       (lrs/get-document lrs
                                                         auth-identity
                                                         params))
           :query (get-multiple-response ctx
                                         (lrs/get-document-ids lrs
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

      ;; State documents
      :xapi.activities.state.PUT.request/params
      :xapi.activities.state.GET.request/params

      :xapi.activities.state.POST.request/params
      :xapi.activities.state.GET.request/params

      :xapi.activities.state.DELETE.request/params
      :xapi.activities.state.GET.request/params

      ;; Activity profile documents
      :xapi.activities.profile.PUT.request/params
      :xapi.activities.profile.GET.request/params

      :xapi.activities.profile.POST.request/params
      :xapi.activities.profile.GET.request/params

      :xapi.activities.profile.DELETE.request/params
      :xapi.activities.profile.GET.request/params

      ;; Agent profile documents
      :xapi.agents.profile.PUT.request/params
      :xapi.agents.profile.GET.request/params

      :xapi.agents.profile.POST.request/params
      :xapi.agents.profile.GET.request/params

      :xapi.agents.profile.DELETE.request/params
      :xapi.agents.profile.GET.request/params))))

(defn etags-preproc
  "Process if-match rules and etags for the handler. Will call `handle-get`
   to check doc state."
  [enter-fn]
  (fn wrap-enter
    [{:keys [xapi
             request
             com.yetanalytics/lrs] :as ctx}]
    (let [;; Destructuring
          {:keys [headers]} request
          ;; VSCode incorrectly marks `if-match` and `if-none-match` as
          ;; if macros
          {hif-match      "if-match"
           hif-none-match "if-none-match"} headers
          ;; Helper fns
          hif-match-ok?
          (fn [ctx hif-match]
            (case hif-match
              nil true
              "*" (= 200 (get-in ctx [:response :status]))
              ;; else
              (contains? (i/etag-header->etag-set hif-match)
                         (::i/etag ctx))))
          hif-none-match-ok?
          (fn [ctx hif-none-match]
            (case hif-none-match
              nil true
              "*" (= 404 (get-in ctx [:response :status]))
              ;; else
              (not (contains? (i/etag-header->etag-set hif-none-match)
                              (::i/etag ctx)))))]
      (if (= nil hif-match hif-none-match)
        ;; If no headers provided, go ahead
        (enter-fn ctx)
        (let [;; TODO: Params overhaul, very silly rn
              get-params-enter   (get-params-enter-fn xapi)
              {get-enter :enter
               get-leave :leave} handle-get]
          (if (p/document-resource-async? lrs)
            ;; Async
            (a/go
              (let [get-ctx
                    (-> ctx
                        (update :request dissoc :body)
                        (assoc-in [:request :request-method] :get)
                        get-params-enter
                        get-enter
                        a/<!
                        get-leave)]
                (if (and (hif-match-ok? get-ctx hif-match)
                         (hif-none-match-ok? get-ctx hif-none-match))
                  (a/<! (enter-fn ctx))
                  (assoc ctx :response
                         (let [{{:keys [status] :as get-response} :response}
                               get-ctx]
                           (if (= 400 status)
                             get-response
                             {:status 412}))))))
            ;; Sync
            (let [get-ctx
                  (-> ctx
                      (update :request dissoc :body)
                      (assoc-in [:request :request-method] :get)
                      get-params-enter
                      get-enter
                      get-leave)]
              (if (and (hif-match-ok? get-ctx hif-match)
                       (hif-none-match-ok? get-ctx hif-none-match))
                (enter-fn ctx)
                (assoc ctx :response
                       (let [{{:keys [status] :as get-response} :response}
                             get-ctx]
                         (if (= 400 status)
                           get-response
                           {:status 412})))))))))))

(s/fdef put-response
  :args (s/cat :ctx map?
               :set-document-ret ::p/set-document-ret))

(defn put-response
  [ctx {:keys [error]}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (assoc ctx :response {:status 204})))


;; TODO: better handling of sync/async, dedupe

(defn- put-err-response
  [ctx {:keys [error document]}]
  (cond
    error
    (assoc ctx
           :io.pedestal.interceptor.chain/error
           error)
    document
    (assoc ctx
           :response
           {:status  409
            :headers {"Content-Type" "text/plain"}
            :body    "If-Match or If-None-Match header is required for existing document."})
    :else
    (assoc ctx
           :response
           {:status 400})))

(defn- ->doc
  "Create a valid document from a request"
  [{:keys [body content-type content-length]}]
  (cond-> {:contents body}
    content-type (assoc :content-type content-type)
    content-length (assoc :content-length content-length)))

(def handle-put
  {:name ::handle-put
   :enter
   (etags-preproc
    (fn handle-put-fn
      [{auth-identity ::auth/identity
        :keys [xapi
               request
               com.yetanalytics/lrs]
        :as ctx}]
      (let [[params-type params]
            (or (find xapi :xapi.activities.state.PUT.request/params)
                (find-some xapi
                           :xapi.activities.profile.PUT.request/params
                           :xapi.agents.profile.PUT.request/params))
            {:keys [headers]} request
            {hif-match      "if-match"
             hif-none-match "if-none-match"} headers
            doc (->doc request)]
        (println "PUT doc:" params " hif-match:" hif-match " hif-none-match:" hif-none-match)
        (if (p/document-resource-async? lrs)
          ;; Async
          (a/go
            (try
              (if (or hif-match hif-none-match)
                ;; has been checked by preproc, OK to proceed
                (put-response ctx
                              (a/<! (lrs/set-document-async
                                     lrs
                                     auth-identity
                                     params
                                     doc
                                     false)))
                ;; no headers, so we check to see if a doc exists
                (let [doc-res (a/<! (lrs/get-document-async
                                     lrs
                                     auth-identity params))]
                  (if (and (or
                            ;; In 2.0, no headers ok for no doc
                            (= "2.0.0"
                               (:com.yetanalytics.lrs/version ctx))
                            ;; prior tests seem to want this for everything but
                            ;; profiles
                            (not (#{:xapi.activities.profile.PUT.request/params
                                    :xapi.agents.profile.PUT.request/params}
                                  params-type)))
                           (not (:error doc-res))
                           (nil? (:document doc-res)))
                    (put-response ctx
                                  (a/<! (lrs/set-document-async
                                         lrs
                                         auth-identity
                                         params
                                         doc
                                         false)))
                    (put-err-response ctx doc-res))))
              (catch #?(:clj Exception :cljs js/Error) ex
                (assoc ctx :io.pedestal.interceptor.chain/error ex))))
          ;; Sync
          (try
            (if (or hif-match hif-none-match)
              (put-response ctx
                            (lrs/set-document
                             lrs
                             auth-identity
                             params
                             doc
                             false))
              ;; if neither header is present
              (let [doc-res (a/<! (lrs/get-document-async
                                   lrs
                                   auth-identity params))]
                (if (and (not (:error doc-res))
                         (nil? (:document doc-res)))
                  (put-response ctx
                                (a/<! (lrs/set-document-async
                                       lrs
                                       auth-identity
                                       params
                                       doc
                                       false)))
                  (put-err-response ctx doc-res))))
            (catch #?(:clj Exception :cljs js/Error) ex
              (assoc ctx :io.pedestal.interceptor.chain/error ex)))))))})

(s/fdef post-response
  :args (s/cat :ctx map?
               :set-document-ret ::p/set-document-ret))

(defn post-response
  [ctx {:keys [error] :as _lrs-response}]
  (if error
    (let [exd (ex-data error)]
      (if (#{:com.yetanalytics.lrs.xapi.document/json-read-error
             :com.yetanalytics.lrs.xapi.document/json-not-object-error
             :com.yetanalytics.lrs.xapi.document/invalid-merge}
           (:type exd))
        (assoc ctx :response {:status 400})
        (assoc ctx :io.pedestal.interceptor.chain/error error)))
    (assoc ctx :response {:status 204})))

(def handle-post
  {:name ::handle-post
   :enter
   (etags-preproc
    (fn handle-post-fn
      [{auth-identity ::auth/identity
        :keys [xapi
               request
               com.yetanalytics/lrs] :as ctx}]
      (let [[_params-spec params]
            (find-some xapi
                       :xapi.activities.state.POST.request/params
                       :xapi.activities.profile.POST.request/params
                       :xapi.agents.profile.POST.request/params)
            doc (->doc request)]
        (if (p/document-resource-async? lrs)
          ;; Async
          (a/go
            (post-response ctx (a/<! (lrs/set-document-async
                                      lrs
                                      auth-identity
                                      params
                                      doc
                                      true))))
          ;; Sync
          (post-response ctx (lrs/set-document
                              lrs
                              auth-identity
                              params
                              doc
                              true))))))})



(s/fdef delete-response
  :args (s/cat :ctx map?
               :delete-document-ret
               (s/or :single ::p/delete-document-ret
                     :multiple ::p/delete-documents-ret)))

(defn delete-response
  [ctx {:keys [error]}]
  (if error
    (assoc ctx :io.pedestal.interceptor.chain/error error)
    (assoc ctx :response {:status 204})))

(def handle-delete
  {:name ::handle-delete
   :enter
   (etags-preproc
    (fn handle-delete-fn
      [{auth-identity ::auth/identity
        :keys [xapi com.yetanalytics/lrs] :as ctx}]
      (if (p/document-resource-async? lrs)
        ;; Async
        (a/go
          (delete-response
           ctx
           (if-let [[_params-spec params]
                    (find-some xapi
                               :xapi.activities.profile.DELETE.request/params
                               :xapi.agents.profile.DELETE.request/params)]
             ;; Activity/Agent profile document
             (a/<! (lrs/delete-document-async lrs auth-identity params))
             ;; State document
             (let [[params-type params]
                   (:xapi.activities.state.DELETE.request/params xapi)]
               (case params-type
                 :id      (a/<! (lrs/delete-document-async
                                 lrs
                                 auth-identity
                                 params))
                 :context (a/<! (lrs/delete-documents-async
                                 lrs
                                 auth-identity
                                 params)))))))
        ;; Sync
        (delete-response
         ctx
         (if-let [[_params-spec params]
                  (find-some xapi
                             :xapi.activities.profile.DELETE.request/params
                             :xapi.agents.profile.DELETE.request/params)]
           ;; Activity/Agent profile document
           (lrs/delete-document lrs auth-identity params)
           ;; State document
           (let [[params-type params]
                 (:xapi.activities.state.DELETE.request/params xapi)]
             (case params-type
               :id      (lrs/delete-document lrs auth-identity params)
               :context (lrs/delete-documents lrs auth-identity params))))))))})
