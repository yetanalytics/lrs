(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.core.async :as a]
            )
  (:import [java.io
            OutputStream
            PipedInputStream
            PipedOutputStream
            PrintWriter]))

(set! *warn-on-reflection* true)

;; TODO: Dynamic boundary?
;; TODO: make this async, work on a servlet output stream

(def crlf "\r\n")

(def boundary
  "105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0")

(def content-type
  (format "multipart/mixed; boundary=%s"
          boundary))

(defn write-attachments [^PrintWriter out attachment-objects]
  (doseq [{:keys [content sha2]
           ctype :contentType
           :as attachment-object} attachment-objects]
    ;; encapsulation boundary
    (.print out crlf)
    (.print out "--")
    (.print out boundary)
    (.print out crlf)

    (.print out (str "Content-Type:" ctype))
    (.print out crlf)
    (.print out "Content-Transfer-Encoding:binary")
    (.print out crlf)
    (.print out (str "X-Experience-API-Hash:" sha2))
    (.print out crlf)
    (.print out crlf)
    (io/copy content out)))

(defn build-multipart ^PipedOutputStream [s-data attachment-objects]
  (let [pipe-in (PipedInputStream.)
        pipe-out (PipedOutputStream.)]
    (.connect pipe-in pipe-out)
    (future ; new thread to prevent blocking deadlock
      (with-open [out (PrintWriter. pipe-out)]
        (binding [*out* out]
          ;; first part is statement results
          (.print ^PrintWriter *out* "--")
          (.print ^PrintWriter *out* boundary)
          (.print ^PrintWriter *out* crlf)

          (.print ^PrintWriter *out* "Content-Type:application/json")
          (.print ^PrintWriter *out* crlf)

          (.print ^PrintWriter *out* crlf)

          (json/generate-stream s-data ^PrintWriter *out*)

          ;; Followed by n more parts, responsible for their own encapsulation
          (write-attachments ^PrintWriter *out* attachment-objects)

          ;; close
          (.print ^PrintWriter *out* crlf)
          (.print ^PrintWriter *out* "--")
          (.print ^PrintWriter *out* boundary)
          (.print ^PrintWriter *out* "--"))))
    pipe-in))

(def statement-result-pre
  "{\"statements\":[")

(def statement-result-post
  "]}")

(defn build-multipart-async
  [statement-result-chan]
  (let [body-chan (a/chan)]
    (a/go
      ;; Begin
      (a/>! body-chan (str "--"
                           boundary
                           crlf
                           "Content-Type:application/json"
                           crlf
                           crlf))
      (loop [stage :init
             s-count 0] ;; s-count is used with multiple statements to enclose
        (when-let [x (a/<! statement-result-chan)]
          (case x
            ;; headers
            :statement
            (do (a/>! body-chan (json/generate-string (a/<! statement-result-chan)))
                (recur :statement s-count))
            :statements
            (do (a/>! body-chan statement-result-pre)
                (recur :statements s-count))
            :more
            (do (a/>! body-chan (format "],\"more\":\"%s\"}"
                                        (a/<! statement-result-chan)))
                (recur :more s-count))
            :attachments
            (do
              ;; when there's no more link, close the statement result
              (when (= stage :statements)
                (a/>! body-chan statement-result-post))
              (recur :attachments s-count))
            ;; now we have a stage, dispatch on that
            (case stage
              :statements
              (do
                ;; maybe Comma
                (when (< 0 s-count)
                  (a/>! body-chan ","))
                ;; Write statement
                (a/>! body-chan (json/generate-string x))
                (recur :statements (inc s-count)))
              :attachments
              (let [{:keys [content contentType sha2] :as attachment-object} x]
                ;; attachment headers
                (a/>! body-chan
                      (str crlf
                           "--"
                           boundary
                           crlf
                           (format "Content-Type:%s" contentType)
                           crlf
                           "Content-Transfer-Encoding:binary"
                           crlf
                           (format "X-Experience-API-Hash:%s" sha2)
                           crlf
                           crlf))
                ;; Attachment Content
                (a/>! body-chan
                      content)
                (recur :attachments s-count))))))
      ;; End
      (a/>! body-chan (str crlf
                           "--"
                           boundary
                           "--"))
      ;; Close the body chan
      (a/close! body-chan))
    body-chan))
