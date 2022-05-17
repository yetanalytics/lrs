(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require
   [clojure.string :as cs]
   #?@(:cljs [[goog.string :refer [format]]
              [goog.string.format]]))
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

;; A la https://www.w3.org/Protocols/rfc1341/7_2_Multipart.html

;; The requirement that the encapsulation boundary begins with a CRLF implies
;; that the body of a multipart entity must itself begin with a CRLF before the
;; first encapsulation line -- that is, if the "preamble" area is not used, the
;; entity headers must be followed by TWO CRLFs. This is indeed how such
;; entities should be composed. A tolerant mail reading program, however, may
;; interpret a body of type multipart that begins with an encapsulation line NOT
;; initiated by a CRLF as also being an encapsulation boundary, but a compliant
;; mail sending program must not generate such entities.
(defn- boundary-pat-open
  [boundary]
  (format "(?:^(?:\\r\\n)?--%s\\r\\n)" boundary))

(defn- boundary-pat-mid
  [boundary]
  (format "(?:\\r\\n--%s\\r\\n)" boundary))

(defn- boundary-pat-close
  [boundary]
  (format "(?:\\r\\n--%s--(?:.|\r\n|\n)*$)" boundary))

(defn make-boundary-pattern
  [boundary]
  (re-pattern
   (str
    ;; First boundary is lax on the first CRLF (see above)
    (boundary-pat-open boundary)
    "|"
    ;; Intermediate boundary
    (boundary-pat-mid boundary)
    "|"
    ;; Terminal boundary
    (boundary-pat-close boundary))))

(defn parse-parts [#?(:clj ^InputStream in
                      :cljs ^String in)
                   ^String boundary]
  (try
    #?(:clj
       (with-open [^Scanner scanner (.useDelimiter
                                     (Scanner. in)
                                     (re-pattern
                                      (str (boundary-pat-mid boundary)
                                           "|"
                                           (boundary-pat-close boundary))))]
         ;; Skip the (anchored) opening boundary or throw
         (.skip scanner
                (re-pattern
                 (boundary-pat-open boundary)))
         (loop [multiparts []]
           (if (.hasNext scanner)
             (let [^String file-chunk (.next scanner)
                   _
                   (assert
                    (not (cs/includes? file-chunk boundary))
                    "Multipart parts must not include boundary.")
                   [headers-str
                    body-str]         (cs/split file-chunk #"\r\n\r\n")
                   headers            (parse-body-headers headers-str)
                   body-bytes         (.getBytes ^String body-str "UTF-8")]
               (recur
                (conj multiparts
                      {:content-type   (get headers "Content-Type")
                       :content-length (count body-bytes)
                       :headers        headers
                       :input-stream   (ByteArrayInputStream. body-bytes)})))
             (do
               ;; Skip the (anchored) close boundary or throw
               (.skip scanner
                      (re-pattern
                       (boundary-pat-close boundary)))
               ;; return multiparts
               multiparts))))
       :cljs
       (let [boundary-pattern (make-boundary-pattern boundary)
             chunks           (cs/split in boundary-pattern)]
         (assert (= "" (first chunks)))
         (into []
               (for [file-chunk (rest chunks)
                     :let [_ (assert
                              (not (cs/includes? file-chunk boundary))
                              "Multipart parts must not include boundary.")
                           [headers-str
                            body-str] (cs/split file-chunk #"\r\n\r\n")
                           headers    (parse-body-headers headers-str)]]
                 {:content-type   (get headers "Content-Type")
                  :content-length (.-length body-str)
                  :headers        headers
                  :input-stream   body-str}))))
    #?@(:clj [(catch AssertionError ae
                (throw (ex-info "Invalid Multipart Body"
                                {:type ::invalid-multipart-body}
                                ae)))
              (catch Exception ex
                (throw (ex-info "Invalid Multipart Body"
                                {:type ::invalid-multipart-body}
                                ex)))]
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
