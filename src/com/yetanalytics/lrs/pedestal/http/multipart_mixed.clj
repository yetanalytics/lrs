(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import
   ;; [javax.servlet MultipartConfigElement]
   ;; [javax.servlet.http Part]
   ;; [org.eclipse.jetty.util MultiPartInputStreamParser]

   [org.apache.commons.mail ByteArrayDataSource]
   [org.apache.commons.fileupload.util LimitedInputStream]
   [javax.mail BodyPart]
   [javax.mail.internet MimeMultipart InternetHeaders$InternetHeader]))

(set! *warn-on-reflection* true)

;; TODO: configurable temp dir
;; TODO: Can't get the jetty way to work, so we use the old one
#_(defn parse-multiparts [req]
  (let [parser (MultiPartInputStreamParser.
                ^java.io.InputStream (:body req)
                ^String (:content-type req)
                MultiPartInputStreamParser/__DEFAULT_MULTIPART_CONFIG
                ^java.io.File (io/file "/tmp"))]
    (assoc req :body-parts
           (mapv
            (fn [^Part part]
              (let [header-names (into []
                                       (.getHeaderNames part))]
                {:content-type (.getContentType part)
                 :headers (into {}
                                (map (fn [^String header-name]
                                       [header-name (.getHeader part
                                                                header-name)])
                                     header-names))
                 :body (.getInputStream part)
                 :content-length (.getSize part)}))
            (.getParts parser)))))

(defn- parse-part-headers [^BodyPart part]
  (let [^java.util.Enumeration header-enum (.getAllHeaders part)]
    (into {}
          (for [^InternetHeaders$InternetHeader header (enumeration-seq header-enum)]
            [(.getName header) (.getValue header)]))))

(defn- parts-sequence
  "Returns a lazy sequence of the parts from a MimeMultipart request"
  ([^MimeMultipart multipart] (parts-sequence multipart (int 0)))
  ([^MimeMultipart multipart ^Integer n]
   (if (< n (.getCount multipart))
     (let [^BodyPart part (.getBodyPart multipart n)]
       (lazy-seq (cons {:input-stream (.getInputStream part)
                        :content-type (.getContentType part)
                        :content-length (.getSize part)
                        :headers (parse-part-headers part)}
                       (parts-sequence multipart (inc n))))))))

(defn- input-stream ^java.io.InputStream [request & [limit]]
  "Returns either the input stream of a size limited input stream if limit is set"
  (if limit
    (proxy [LimitedInputStream] [(:body request) limit]
      (raiseError [max-size count]
        (throw (ex-info (format "The body exceeds its maximum permitted size of %s bytes" max-size)
                        {:type ::too-much-content
                         :max-size max-size
                         :count count}))))
    (:body request)))

(defn parse-request
  "Parse a mutlipart/mixed request"
  [request & [limit]]
  (let [multipart (MimeMultipart. (ByteArrayDataSource.
                                   (input-stream request (or limit
                                                             (int 1048576)))
                                   "multipart/mixed"))]
    (if (.isComplete multipart)
      (parts-sequence multipart)
      (throw (ex-info "Incomplete Multipart Request"
                      {:type ::incomplete-multipart})))))

(defn parse-multiparts [req]
  (assoc req :multiparts
         (parse-request req)))
