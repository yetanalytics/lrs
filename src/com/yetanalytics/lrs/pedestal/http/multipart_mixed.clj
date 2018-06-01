(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as cs])
  (:import
   [java.util Scanner]
   [java.io ByteArrayInputStream InputStream]))

(set! *warn-on-reflection* true)

;; TODO: configurable temp dir

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Simpler impl w/scanner

(defn parse-body-headers
  "Given a string of line-separated body headers, parse them into a string map"
  [^String headers-str]
  (into {}
        (for [h-str (cs/split-lines headers-str)]
          (mapv cs/trim
                (cs/split h-str #"\s*:\s*" 2)))))

(defn parse-parts [^InputStream in
                   ^String boundary]
  (try
    (let [boundary-pattern (re-pattern (str "(?m)\\R?^--" boundary "(--)?$\\R?"))]
      (with-open [scanner (Scanner. in)]
        (assert (.hasNext scanner boundary-pattern)
                "No initial multipart boundary.")
        (into []
              (for [file-chunk (iterator-seq (.useDelimiter scanner boundary-pattern))
                    :let [[headers-str body-str] (cs/split file-chunk #"\R\R")
                          headers (parse-body-headers headers-str)
                          body-bytes (.getBytes ^String body-str "UTF-8")]]
                {:content-type (get headers "Content-Type")
                 :content-length (count body-bytes)
                 :headers headers
                 :input-stream (ByteArrayInputStream. body-bytes)}))))
    (catch AssertionError ae
      (throw (ex-info "Invalid Multipart Body"
                      {:type ::invalid-multipart-body})))
    (catch Exception ex
      (throw (ex-info "Incomplete Multipart Request"
                      {:type ::incomplete-multipart})))))

(defn find-boundary
  "Find a boundary in a content type, or nil"
  ^String [^String ctype]
  (let [[m quoted unquoted]
        (re-find #"(?:^\s*multipart/mixed\s*;\s*boundary\s*=\s*)(?:(?:\"(.*)\".*$)|(?:([a-zA-Z0-9\'\+\-\_]*)$))"
                 ctype)]
    (when m
      (or quoted unquoted))))
