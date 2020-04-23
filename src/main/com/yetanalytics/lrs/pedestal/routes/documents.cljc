(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [clojure.string :as cstr]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            #?(:clj [cheshire.core :as json])))

(defn find-some [m & kws]
  (some (partial find m)
        kws))

;; TODO: Handle io correctly

;; TODO: Hoist etags to global interceptor
(def etag-string-pattern
  #"\w+")

(defn etag-header->etag-set
  [etag-header]
  (into #{} (re-seq etag-string-pattern etag-header)))

;; TODO: better handling of sync/async, dedupe
(def handle-put
  {:name ::handle-put
   :enter (fn [{auth-identity ::auth/identity
                :keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (if (p/document-resource-async? lrs)
              (a/go
                (let [{:keys [body content-type content-length headers]} request]
                  (if-let [[_ params] (find xapi :xapi.activities.state.PUT.request/params)]
                    (do
                      (a/<! (lrs/set-document-async
                             lrs auth-identity params
                             {:content-type content-type
                              :content-length content-length
                              :contents body}
                             false))
                      (assoc ctx :response {:status 204}))
                    (let [[params-spec params] (find-some xapi
                                                          :xapi.activities.profile.PUT.request/params
                                                          :xapi.agents.profile.PUT.request/params)
                          {:strs [if-match if-none-match]} headers]
                      (if (or if-match if-none-match)
                        (do (a/<! (lrs/set-document-async
                                   lrs auth-identity params
                                   {:content-type content-type
                                    :content-length content-length
                                    :contents body}
                                   false))
                            (assoc ctx :response {:status 204}))
                        ;; if neither header is present
                        (if (:document (a/<! (lrs/get-document-async
                                              lrs auth-identity params)))
                          (assoc ctx :response {:status 409
                                                :headers {"Content-Type" "text/plain"}
                                                :body "If-Match or If-None-Match header is required for existing document."})
                          (assoc ctx :response {:status 400})))))))
              (let [{:keys [body content-type content-length headers]} request]
                  (if-let [[_ params] (find xapi :xapi.activities.state.PUT.request/params)]
                    (do
                      (lrs/set-document
                       lrs auth-identity params
                       {:content-type content-type
                        :content-length content-length
                        :contents body}
                       false)
                      (assoc ctx :response {:status 204}))
                    (let [[params-spec params] (find-some xapi
                                                          :xapi.activities.profile.PUT.request/params
                                                          :xapi.agents.profile.PUT.request/params)
                          {:strs [if-match if-none-match]} headers]
                      (if (or if-match if-none-match)
                        (do (lrs/set-document
                             lrs auth-identity params
                             {:content-type content-type
                              :content-length content-length
                              :contents body}
                             false)
                            (assoc ctx :response {:status 204}))
                        ;; if neither header is present
                        (if (:document (a/<! (lrs/get-document lrs auth-identity params)))
                          (assoc ctx :response {:status 409
                                                :headers {"Content-Type" "text/plain"}
                                                :body "If-Match or If-None-Match header is required for existing document."})
                          (assoc ctx :response {:status 400}))))))))})

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
   :enter (fn [{auth-identity ::auth/identity
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
                                    true)))))})

(defn get-single-response
  [{:keys [xapi
           request
           com.yetanalytics/lrs] :as ctx}
   {?document :document
    ?etag :etag :as lrs-response}]
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
    (assoc ctx :response {:status 404})))

(defn get-multiple-response
  [{:keys [xapi
           request
           com.yetanalytics/lrs] :as ctx}
   {ids :document-ids
    ?etag :etag :as lrs-response}]
  (cond->
      (assoc ctx :response {:headers {"Content-Type" "application/json"}
                            :status 200
                            :body (-> (into [] ids)
                                      #?@(:clj [json/generate-string]
                                          :cljs [clj->js
                                                 (->> (.stringify js/JSON))])
                                   )})
    ?etag (assoc ::i/etag ?etag)))

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
                      :xapi.agents.profile.GET.request/params)
           async? (p/document-resource-async? lrs)]
       (case params-type
         :id
         (if async?
           (a/go (get-single-response ctx (a/<! (lrs/get-document-async
                                                 lrs auth-identity params))))
           (get-single-response ctx (lrs/get-document lrs auth-identity params)))
         :query
         (if async?
           (a/go (get-multiple-response ctx
                                        (a/<!
                                         (lrs/get-document-ids-async
                                          lrs auth-identity params))))
           (get-multiple-response ctx (lrs/get-document-ids lrs
                                                            auth-identity
                                                            params)))
         (assoc ctx :response {:status 400}))))})

(def handle-delete
  {:name ::handle-delete
   :enter (fn [{auth-identity ::auth/identity
                :keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (if (p/document-resource-async? lrs)
              (a/go
                (if-let [[params-spec params] (find-some xapi
                                                         :xapi.activities.profile.DELETE.request/params
                                                         :xapi.agents.profile.DELETE.request/params)]
                  (a/<! (lrs/delete-document-async lrs auth-identity params))
                  (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                    (case params-type
                      :id (a/<! (lrs/delete-document-async
                                 lrs auth-identity params))
                      :context (a/<! (lrs/delete-documents-async
                                      lrs auth-identity params)))))
                (assoc ctx :response {:status 204}))
              (do
                (if-let [[params-spec params] (find-some xapi
                                                         :xapi.activities.profile.DELETE.request/params
                                                         :xapi.agents.profile.DELETE.request/params)]
                  (lrs/delete-document lrs auth-identity params)
                  (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                    (case params-type
                      :id (lrs/delete-document lrs auth-identity params)
                      :context (lrs/delete-documents lrs auth-identity params))))
                (assoc ctx :response {:status 204}))))})
