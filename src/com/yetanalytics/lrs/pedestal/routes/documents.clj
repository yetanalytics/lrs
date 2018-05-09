(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [com.google.common.io ByteStreams]
           [java.io InputStream ByteArrayOutputStream]
           [java.nio ByteBuffer]))
;; TODO: Handle io correctly

(def handle-put
  {:name ::handle-put
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{[doc-type params] ::p/set-document-params} xapi
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
            (let [{[doc-type params] ::p/set-document-params} xapi
                  {:keys [body content-type content-length]} request]
              (try (lrs/set-document
                    lrs params
                    {:content-type content-type
                     :content-length content-length
                     :contents (ByteStreams/toByteArray ^InputStream body)}
                    true)
                   (assoc ctx :response {:status 204})
                   (catch clojure.lang.ExceptionInfo exi
                     (let [exd (ex-data exi)]
                       (case (:type exd)
                         :com.yetanalytics.lrs.xapi.document/invalid-merge
                         (assoc ctx :response {:status 400})
                         (throw exi)))))))})

(def handle-get
  {:name ::handle-get
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{[card [doc-type params]]
                   ::p/get-document-all-params} xapi
                  ]
              (case card
                :single
                (if-let [{:keys [content-type
                                 content-length
                                 contents
                                 id
                                 updated] :as result} (lrs/get-document lrs params)]
                  (assoc ctx :response {:status 200
                                        :headers {"Content-Type" content-type
                                                  "Content-Length" (str content-length)
                                                  "Last-Modified" updated}
                                        :body (ByteBuffer/wrap ^bytes contents) #_(ByteArrayOutputStream. ^bytes contents)
                                        #_(if (.startsWith ^String content-type "application/json")
                                            (json/generate-string contents)
                                            (String. ^bytes contents "UTF-8"))})
                  (assoc ctx :response {:status 404}))
                :multiple
                (if-let [result (lrs/get-document-ids lrs params)]
                  (assoc ctx :response {:status 200
                                        :body result})
                  (assoc ctx :response {:status 200
                                        :body []})))))})

(def handle-delete
  {:name ::handle-delete
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [{[card [doc-type params]]
                   ::p/delete-document-all-params} xapi]
              (case card
                :single (lrs/delete-document lrs params)
                :multiple (lrs/delete-documents lrs params))
              (assoc ctx :response {:status 204})))})
