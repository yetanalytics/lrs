(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.document
  "Document Interceptors"
  (:require
   [io.pedestal.interceptor.chain :as chain]
   [com.yetanalytics.lrs.util :as u]
   #?(:clj [clojure.java.io :as io])
   #?@(:cljs [[goog.string :refer [format]]
              [goog.string.format]]))
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
       (if-let [scan-error (try
                             (file-scanner
                              #?(:clj (ByteArrayInputStream. body-bytes)
                                 :cljs body-bytes))
                             (catch #?(:clj Exception :cljs js/Error) _
                               {:message "Scan Error"}))]
         (assoc (chain/terminate ctx)
                :response
                {:status 400
                 :headers {"Content-Type" "application/json"}
                 :body (u/json-string
                        {:error
                         {:message
                          (format "Document scan failed, Error: %s"
                                  (:message scan-error))}})})
         (assoc-in ctx
                   [:request :body]
                   #?(:clj (ByteArrayInputStream. body-bytes)
                      :cljs body-bytes)))))})
