(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
  (:require [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.io
            OutputStream
            PipedInputStream
            PipedOutputStream
            PrintWriter]))

(set! *warn-on-reflection* true)

;; TODO: Dynamic boundary?
;; TODO: make this async, work on a servlet output stream

(def clrf "\r\n")

(def boundary
  "105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0")

(defn write-attachments [^PrintWriter out attachment-objects]
  (doseq [{:keys [input-stream content-type sha2] :as attachment-object} attachment-objects]
    ;; encapsulation boundary
    (.print out clrf)
    (.print out "--")
    (.print out boundary)
    (.print out clrf)

    (.print out (str "Content-Type:" content-type))
    (.print out clrf)
    (.print out "Content-Transfer-Encoding:binary")
    (.print out clrf)
    (.print out (str "X-Experience-API-Hash:" sha2))
    (.print out clrf)
    (.print out clrf)
    (io/copy input-stream out)))

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
          (.print ^PrintWriter *out* clrf)

          (.print ^PrintWriter *out* "Content-Type:application/json")
          (.print ^PrintWriter *out* clrf)

          (.print ^PrintWriter *out* clrf)

          (json/generate-stream s-data ^PrintWriter *out*)

          ;; Followed by n more parts, responsible for their own encapsulation
          (write-attachments ^PrintWriter *out* attachment-objects)

          ;; close
          (.print ^PrintWriter *out* clrf)
          (.print ^PrintWriter *out* "--")
          (.print ^PrintWriter *out* boundary)
          (.print ^PrintWriter *out* "--"))))
    pipe-in))
