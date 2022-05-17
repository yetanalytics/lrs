(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require
   [clojure.string :as cs]
   #?@(:cljs [[com.yetanalytics.lrs.util :as u]
              [goog.string :refer [format]]
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
  (format "^(\\r\\n)?--%s\\r\\n" boundary))

(defn- boundary-pat-mid
  [boundary]
  (format "(?<!^)\\r\\n--%s\\r\\n(?!$)" boundary))

(defn- boundary-pat-close
  [boundary]
  (format "\\r\\n--%s--(\r\n|\n)*$" boundary))

(defn- assert-valid
  [test message type-k]
  (when-not test
    (throw (ex-info message
                    {:type type-k}))))

#?(:cljs
   (defn split-multiparts
     "Splits multipart body parts, ensuring start + end and at least 2 parts"
     [boundary body]
     (let [[[open-idx open-bound]
            :as open-re-pos] (u/re-pos
                              (re-pattern
                               (boundary-pat-open boundary))
                              body)
           mid-re-pos (u/re-pos
                       (re-pattern
                        (boundary-pat-mid boundary))
                       body)
           [[close-idx close-bound]
            :as close-re-pos] (u/re-pos
                               (re-pattern
                                (boundary-pat-close boundary))
                               body)
           all-pos (concat open-re-pos
                           mid-re-pos
                           close-re-pos)]
       (assert-valid (= 1 (count open-re-pos))
                     "Only one opening boundary can be present"
                     ::invalid-one-opening-boundary)
       (assert-valid (= 0 open-idx)
                     "Opening boundary must begin the string"
                     ::invalid-pos-opening-boundary)
       (assert-valid (<= 1 (count mid-re-pos))
                     "At least one mid boundary must be present"
                     ::invalid-at-least-one-mid-boundary)
       (assert-valid (= 1 (count close-re-pos))
                     "Only one closing boundary can be present"
                     ::invalid-one-closing-boundary)
       (assert-valid (= (count body)
                        (+ close-idx (count close-bound)))
                     "Closing boundary must end string"
                     ::invalid-pos-closing-boundary)
       (assert-valid (distinct? all-pos)
                     "All boundary positions must be distinct"
                     ::invalid-distinct-boundary-pos)
       (for [[[idx-a
               bound-a :as a]
              [idx-b :as b]] (partition 2 1 all-pos)]
         (subs body
               (+ idx-a
                  (count bound-a))
               idx-b)))))

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
                   (assert-valid
                    (not (cs/includes? file-chunk boundary))
                    "Multipart parts must not include boundary."
                    ::invalid-no-boundary-in-multipart)
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
       (into []
             (for [file-chunk (split-multiparts boundary in)
                   :let [_
                         (assert-valid
                          (not (cs/includes? file-chunk boundary))
                          "Multipart parts must not include boundary."
                          ::invalid-no-boundary-in-multipart)
                         [headers-str
                          body-str] (cs/split file-chunk #"\r\n\r\n")
                         headers    (parse-body-headers headers-str)]]
               {:content-type   (get headers "Content-Type")
                :content-length (.-length body-str)
                :headers        headers
                :input-stream   body-str})))
    (catch #?(:clj Exception
              :cljs js/Error) ex
      (throw (ex-info "Invalid Multipart Body"
                      {:type ::invalid-multipart-body}
                      ex)))))

(def content-type-regex
  #"(?:^\s*multipart/mixed\s*;\s*boundary\s*=\s*)(?:(?:\"(.*)\".*$)|(?:([a-zA-Z0-9\'\+\-\_]*)$))")

(defn find-boundary
  "Find a boundary in a content type, or nil"
  ^String [^String ctype]
  (let [[m quoted unquoted] (re-find content-type-regex ctype)]
    (when m
      (or quoted unquoted))))
