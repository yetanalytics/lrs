(ns com.yetanalytics.lrs.pedestal.routes.documents
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [com.google.common.io ByteStreams]
           [java.io InputStream ByteArrayOutputStream]
           [java.nio ByteBuffer]))

(defn find-some [m & kws]
  (some (partial find m)
        kws))

;; TODO: Handle io correctly

(def handle-put
  {:name ::handle-put
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (let [[params-spec params] (find-some xapi
                                                  :xapi.activities.state.PUT.request/params
                                                  :xapi.activities.profile.PUT.request/params
                                                  :xapi.agents.profile.PUT.request/params)
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
            (let [[params-spec params] (find-some xapi
                                                  :xapi.activities.state.POST.request/params
                                                  :xapi.activities.profile.POST.request/params
                                                  :xapi.agents.profile.POST.request/params)
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
   :enter
   (fn [{:keys [xapi
                request
                com.yetanalytics/lrs] :as ctx}]
     (let [[params-spec [params-type params]]
           (find-some xapi
                      :xapi.activities.state.GET.request/params
                      :xapi.activities.profile.GET.request/params
                      :xapi.agents.profile.GET.request/params)]

       (case params-type
         :id
         (if-let [{:keys [content-type
                          content-length
                          contents
                          id
                          updated] :as result} (lrs/get-document lrs params)]
           (assoc ctx :response {:status 200
                                 :headers {"Content-Type" content-type
                                           "Content-Length" (str content-length)
                                           "Last-Modified" updated}
                                 :body (ByteBuffer/wrap ^bytes contents) #_(ByteArrayOutputStream. ^bytes contents)})
           (assoc ctx :response {:status 404}))
         :query
         (assoc ctx :response {:headers {"Content-Type" "application/json"}
                               :status 200
                               :body (json/generate-string
                                      (into [] (lrs/get-document-ids lrs params)))}))))})

(def handle-delete
  {:name ::handle-delete
   :enter (fn [{:keys [xapi
                       request
                       com.yetanalytics/lrs] :as ctx}]
            (if-let [params (find-some xapi
                                       :xapi.activities.profile.DELETE.request/params
                                       :xapi.agents.profile.DELETE.request/params)]
              (lrs/delete-document lrs params)
              (let [[params-type params] (:xapi.activities.state.DELETE.request/params xapi)]
                (case params-type
                  :id (lrs/delete-document lrs params)
                  :context (lrs/delete-documents lrs params))))
            (assoc ctx :response {:status 204}))})
