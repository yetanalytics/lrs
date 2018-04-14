(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [com.google.common.io ByteStreams]
           [java.io InputStream]))
;; TODO: Handle io correctly

(def handle-put
  {:name ::handle-put
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{params ::p/set-document-params} xapi
                  {:keys [body content-type content-length]} request]
              (lrs/set-document
               lrs params
               {:content-type content-type
                :content-length content-length
                :contents (ByteStreams/toByteArray ^InputStream body)}
               false)
              (assoc ctx :response {:status 204})))})

(def handle-post
  {:name ::handle-post
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{params ::p/set-document-params} xapi
                  {:keys [body content-type content-length]} request]
              (lrs/set-document
               lrs params
               {:content-type content-type
                :content-length content-length
                :contents (ByteStreams/toByteArray ^InputStream body)}
               true)
              (assoc ctx :response {:status 204})))})

(def handle-get
  {:name ::handle-get
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{{single-params :single
                    ids-params :ids}
                   ::p/get-document-any-params} xapi
                  ]
              (if-let [result (if ids-params
                                (lrs/get-document-ids lrs ids-params)
                                (lrs/get-document lrs single-params))]
                (assoc ctx :response {:status 200
                                      :body result})
                (assoc ctx :response {:status 404}))))})

(def handle-delete
  {:name ::handle-delete
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{{single-params :single
                    ids-params :ids}
                   ::p/delete-document-any-params} xapi
                  ]
              (if ids-params
                (lrs/delete-documents lrs ids-params)
                (lrs/delete-document lrs single-params))
              (assoc ctx :response {:status 204})))})
