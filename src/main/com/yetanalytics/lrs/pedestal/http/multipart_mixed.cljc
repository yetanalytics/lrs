(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require
   [clojure.string :as cs])
  #?(:clj (:import [java.util Scanner]
                   [java.io ByteArrayInputStream InputStream])))

;; TODO: configurable temp dir

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Simpler impl w/scanner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-body-headers
  "Given a string of line-separated body headers, parse them into a string map"
  [^String headers-str]
  (into {}
        (for [h-str (cs/split-lines headers-str)]
          (mapv cs/trim
                (cs/split h-str #"\s*:\s*" 2)))))

;; TODO: Test!!!
(defn- make-boundary-pattern
  [boundary]
  (re-pattern (str "(?m)\\R?^--" boundary "(?:--)?$\\R?")))

(defn parse-parts [#?(:clj ^InputStream in
                      :cljs ^String in)
                   ^String boundary]
  (try
    #?(:clj
       (let [boundary-pattern (make-boundary-pattern boundary)]
         (with-open [scanner (Scanner. in)]
           (assert (.hasNext scanner boundary-pattern)
                   "No initial multipart boundary.")
           (into []
                 (for [file-chunk (iterator-seq (.useDelimiter
                                                 scanner
                                                 boundary-pattern))
                       :let [[headers-str
                              body-str] (cs/split file-chunk #"\R{2}")
                             headers    (parse-body-headers headers-str)
                             body-bytes (.getBytes ^String body-str "UTF-8")]]
                   {:content-type   (get headers "Content-Type")
                    :content-length (count body-bytes)
                    :headers        headers
                    :input-stream   (ByteArrayInputStream. body-bytes)}))))
       :cljs
       (let [boundary-pattern (make-boundary-pattern boundary)
             chunks           (cs/split (cs/trim in) boundary-pattern)]
         (assert (= "" (first chunks)))
         (into []
               (for [file-chunk (rest chunks)
                     ;; Using cs/split in cljs leaves extra crlfs around content
                     ;; so we remove them with trim
                     :let [file-chunk (cs/trim file-chunk)
                           [headers-str
                            body-str] (cs/split file-chunk #"\r\n\r\n")
                           headers    (parse-body-headers headers-str)]]
                 {:content-type   (get headers "Content-Type")
                  :content-length (.-length body-str)
                  :headers        headers
                  :input-stream   body-str}))))
    #?@(:clj [(catch AssertionError _
                (throw (ex-info "Invalid Multipart Body"
                                {:type ::invalid-multipart-body})))
              (catch Exception _
                (throw (ex-info "Incomplete Multipart Request"
                                {:type ::incomplete-multipart})))]
        :cljs [(catch js/Error jse
                 (throw (ex-info "Invalid Multipart Body"
                                 {:type ::invalid-multipart-body}
                                 jse)))])))

(def content-type-regex
  #"(?:^\s*multipart/mixed\s*;\s*boundary\s*=\s*)(?:(?:\"(.*)\".*$)|(?:([a-zA-Z0-9\'\+\-\_]*)$))")

(defn find-boundary
  "Find a boundary in a content type, or nil"
  ^String [^String ctype]
  (let [[m quoted unquoted] (re-find content-type-regex ctype)]
    (when m
      (or quoted unquoted))))
