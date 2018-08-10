(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.string :as cs]
   #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import
           [java.util Scanner]
           [java.io ByteArrayInputStream InputStream])))

#?(:clj (set! *warn-on-reflection* true))

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

;; TODO: Test!!!
(defn parse-parts [#?(:clj ^InputStream in
                      :cljs ^String in)
                   ^String boundary]
  (try
    (let [boundary-pattern (re-pattern (str "(?m)\\R?^--" boundary "(?:--)?$\\R?"))]
      #?(:clj (with-open [scanner (Scanner. in)]
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
                         :input-stream (ByteArrayInputStream. body-bytes)})))
         :cljs
         (into []
               (for [file-chunk (cs/split (cs/trim in) boundary-pattern)
                     :when (not-empty file-chunk)
                     ;; Using cs/split in cljs leaves extra crlfs around content
                     ;; so we remove them with trim
                     :let [file-chunk (cs/trim file-chunk)
                           [headers-str body-str] (cs/split file-chunk #"\r\n\r\n")
                           headers (parse-body-headers headers-str)]]
                 {:content-type (get headers "Content-Type")
                  :content-length (.-length body-str)
                  :headers headers
                  :input-stream body-str}))))
    #?@(:clj [(catch AssertionError ae
                (throw (ex-info "Invalid Multipart Body"
                                {:type ::invalid-multipart-body})))
              (catch Exception ex
                (throw (ex-info "Incomplete Multipart Request"
                                {:type ::incomplete-multipart})))]
        :cljs [(catch js/Error jse
                 (throw (ex-info "Invalid Multipart Body"
                                 {:type ::invalid-multipart-body}
                                 jse)))])))

(defn find-boundary
  "Find a boundary in a content type, or nil"
  ^String [^String ctype]
  (let [[m quoted unquoted]
        (re-find #"(?:^\s*multipart/mixed\s*;\s*boundary\s*=\s*)(?:(?:\"(.*)\".*$)|(?:([a-zA-Z0-9\'\+\-\_]*)$))"
                 ctype)]
    (when m
      (or quoted unquoted))))
