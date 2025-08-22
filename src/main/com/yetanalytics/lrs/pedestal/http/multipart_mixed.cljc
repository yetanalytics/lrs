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

(defn- boundary-pat-mid-simple
  [boundary]
  (format "\\r\\n--%s\\r\\n" boundary))

#?(:cljs
   (defn split-multiparts
     "Splits multipart body parts, ensuring start + end and at least 1 part"
     [boundary body]
     (let [open-re-pos (u/re-pos
                        (re-pattern
                         (boundary-pat-open boundary))
                        body)
           ;; Use the same mid pattern as JVM, but without negative lookbehind
           mid-re-pos (u/re-pos
                       (re-pattern
                        (boundary-pat-mid-simple boundary))
                       body)
           close-re-pos (u/re-pos
                         (re-pattern
                          (boundary-pat-close boundary))
                         body)

           ;; Filter out any mid matches that are actually part of the opening
           actual-mid-pos
           (filter (fn [[idx _]]
                     (let [open-end (when-let [[open-idx open-match] (first open-re-pos)]
                                      (+ open-idx (count open-match)))]
                       (or (nil? open-end) (>= idx open-end))))
                   mid-re-pos)

           all-pos (concat open-re-pos
                           actual-mid-pos
                           close-re-pos)]

       (assert-valid (= 1 (count open-re-pos))
                     "Only one opening boundary can be present"
                     ::invalid-one-opening-boundary)
       (assert-valid (>= (count actual-mid-pos) 0)
                     "Mid boundaries must be valid"
                     ::invalid-mid-boundaries)
       (assert-valid (= 1 (count close-re-pos))
                     "Only one closing boundary can be present"
                     ::invalid-one-closing-boundary)

       (for [[[idx-a bound-a] [idx-b]] (partition 2 1 all-pos)]
         (subs body
               (+ idx-a (count bound-a))
               idx-b)))))

(defn parse-part [^String part boundary]
  (assert-valid
   (not (cs/includes? part boundary))
   "Multipart parts must not include boundary."
   ::invalid-no-boundary-in-multipart)
  (let [[headers-str
         body-str] (cs/split part #"\r\n\r\n")
        headers    (parse-body-headers headers-str)
        #?@(:clj [body-bytes (.getBytes body-str "UTF-8")])]

    {:content-type   (get headers "Content-Type")
     :content-length #?(:clj (count body-bytes)
                        :cljs (.-length body-str))
     :headers        headers
     :input-stream   #?(:clj (ByteArrayInputStream. body-bytes)
                        :cljs body-str)}))

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
             (recur (conj multiparts (parse-part (.next scanner) boundary)))
             (do
               ;; Skip the (anchored) close boundary or throw
               (.skip scanner
                      (re-pattern
                       (boundary-pat-close boundary)))
               ;; return multiparts
               multiparts))))
       :cljs
       (into []
             (map #(parse-part % boundary)
                  (split-multiparts boundary in))))
    (catch #?(:clj Exception
              :cljs js/Error) ex
      #?(:cljs (.log js/console "error" ex))
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
