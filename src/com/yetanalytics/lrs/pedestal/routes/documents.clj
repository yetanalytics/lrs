(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [clojure.core.async :as a]
            [io.pedestal.log :as log])
  (:import #_[com.google.common.io ByteStreams]
           [java.io InputStream ByteArrayOutputStream]
           [java.nio ByteBuffer]))

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
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (if (p/document-resource-async? lrs)
              (a/go
                (let [{:keys [body content-type content-length headers]} request]
                  (if-let [[_ params] (find xapi :xapi.activities.state.PUT.request/params)]
                    (do
                      (a/<! (lrs/set-document-async
                             lrs params
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
                                   lrs params
                                   {:content-type content-type
                                    :content-length content-length
                                    :contents body}
                                   false))
                            (assoc ctx :response {:status 204}))
                        ;; if neither header is present
                        (if (:document (a/<! (lrs/get-document-async lrs params)))
                          (assoc ctx :response {:status 409
                                                :body "If-Match or If-None-Match header is required for existing document."})
                          (assoc ctx :response {:status 400})))))))
              (let [{:keys [body content-type content-length headers]} request]
                  (if-let [[_ params] (find xapi :xapi.activities.state.PUT.request/params)]
                    (do
                      (lrs/set-document
                       lrs params
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
                             lrs params
                             {:content-type content-type
                              :content-length content-length
                              :contents body}
                             false)
                            (assoc ctx :response {:status 204}))
                        ;; if neither header is present
                        (if (:document (a/<! (lrs/get-document lrs params)))
                          (assoc ctx :response {:status 409
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
        (throw error)))
    (assoc ctx :response {:status 204})))

(def handle-post
  {:name ::handle-post
   :enter (fn [{:keys [xapi
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
                                            lrs params
                                            {:content-type content-type
                                             :content-length content-length
                                             :contents body}
                                            true))))
                (post-response ctx (lrs/set-document
                                    lrs params
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
                            :body (json/generate-string
                                   (into [] ids))})
    ?etag (assoc ::i/etag ?etag)))

(def handle-get
  {:name ::handle-get
   :enter
   (fn [{:keys [xapi
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
           (a/go (get-single-response ctx (a/<! (lrs/get-document-async lrs params))))
           (get-single-response ctx (lrs/get-document lrs params)))
         :query
         (if async?
           (a/go (get-multiple-response ctx (a/<! (lrs/get-document-ids-async lrs params))))
           (get-multiple-response ctx (lrs/get-document-ids lrs params)))
         (assoc ctx :response {:status 400}))))})

(def handle-delete
  {:name ::handle-delete
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (if (p/document-resource-async? lrs)
              (a/go
                (if-let [[params-spec params] (find-some xapi
                                                         :xapi.activities.profile.DELETE.request/params
                                                         :xapi.agents.profile.DELETE.request/params)]
                  (a/<! (lrs/delete-document-async lrs params))
                  (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                    (case params-type
                      :id (a/<! (lrs/delete-document-async lrs params))
                      :context (a/<! (lrs/delete-documents-async lrs params)))))
                (assoc ctx :response {:status 204}))
              (do
                (if-let [[params-spec params] (find-some xapi
                                                         :xapi.activities.profile.DELETE.request/params
                                                         :xapi.agents.profile.DELETE.request/params)]
                  (lrs/delete-document lrs params)
                  (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                    (case params-type
                      :id (lrs/delete-document lrs params)
                      :context (lrs/delete-documents lrs params))))
                (assoc ctx :response {:status 204}))))})
