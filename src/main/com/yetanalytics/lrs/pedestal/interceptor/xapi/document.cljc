(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.document
  "Document Interceptors"
  (:require
   [io.pedestal.interceptor.chain :as chain]
   #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import [java.io ByteArrayOutputStream ByteArrayInputStream])))

#?(:clj
   (defn stream->bytes
     [input-stream]
     (let [baos (ByteArrayOutputStream.)]
       (io/copy input-stream baos)
       (.toByteArray baos))))

(defn scan-document
  "Return an interceptor that will scan document request bodies given a
  file-scanner fn. Reads body into a byte array (in clojure)"
  [file-scanner]
  {:name ::scan-document
   :enter
   (fn [ctx]
     (let [body-bytes (-> ctx
                          (get-in [:request :body])
                          #?(:clj stream->bytes :cljs identity))]
       (if-let [scan-error (file-scanner
                            #?(:clj (ByteArrayInputStream. body-bytes)
                               :cljs body-bytes))]
         (assoc (chain/terminate ctx)
                :response
                {:status 400
                 :body {:error
                        {:message
                         (format "Document scan failed, Error: %s"
                                 (:message scan-error))}}})
         (assoc-in ctx
                   [:request :body]
                   #?(:clj (ByteArrayInputStream. body-bytes)
                      :cljs body-bytes)))))})
