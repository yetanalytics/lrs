(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed
  (:require
   [clojure.string :as cs]
   #?@(:cljs [[com.yetanalytics.lrs.util :as u]
              [goog.string :refer [format]]
              [goog.string.format]]))
  #?(:clj (:import [java.io BufferedInputStream ByteArrayInputStream ByteArrayOutputStream InputStream PushbackInputStream])))

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
         body-str] (cs/split part #"\r\n\r\n" 2)
        headers    (parse-body-headers headers-str)
        #?@(:clj [body-bytes (.getBytes body-str "UTF-8")])]

    {:content-type   (get headers "Content-Type")
     :content-length #?(:clj (count body-bytes)
                        :cljs (.-length body-str))
     :headers        headers
     :input-stream   #?(:clj (ByteArrayInputStream. body-bytes)
                        :cljs body-str)}))


#?(:clj
   (do
     (defn- multipart-byte-index
       "Find the first exact occurrence of a byte sequence."
       [^bytes bytes ^bytes needle]
       (let [limit (- (alength bytes) (alength needle))]
         (loop [i 0]
           (when (<= i limit)
             (if (loop [j 0]
                   (if (= j (alength needle))
                     true
                     (and (= (aget bytes (+ i j)) (aget needle j))
                          (recur (inc j)))))
               i
               (recur (inc i)))))))

     (defn- parse-part-bytes
       "Decode only MIME headers; preserve the complete attachment body as bytes."
       [^bytes bytes]
       (let [divider (.getBytes "\r\n\r\n" "US-ASCII")
             index   (multipart-byte-index bytes divider)]
         (assert-valid (some? index)
                       "Multipart part has no header/body separator"
                       ::invalid-multipart-part)
         (let [headers (parse-body-headers
                        (String. bytes 0 index "ISO-8859-1"))
               offset  (+ index (alength divider))
               length  (- (alength bytes) offset)]
           {:content-type   (get headers "Content-Type")
            :content-length length
            :headers        headers
            :input-stream   (ByteArrayInputStream. bytes offset length)})))

     (defn- read-multipart-part
       "Read raw bytes up to a syntactically valid MIME boundary.
       A boundary-like sequence inside the payload is preserved verbatim."
       [^PushbackInputStream in ^bytes marker]
       (let [part (ByteArrayOutputStream.)
             size (alength marker)]
         (loop [matched 0]
           (let [b (.read in)]
             (assert-valid (not= -1 b)
                           "Multipart body is missing its closing boundary"
                           ::invalid-multipart-boundary)
             (if (= b (bit-and 0xff (aget marker matched)))
               (if (= (inc matched) size)
                 (let [suffix-a (.read in)
                       suffix-b (.read in)]
                   (cond
                     (and (= 13 suffix-a) (= 10 suffix-b))
                     {:part (.toByteArray part) :closed? false}

                     (and (= 45 suffix-a) (= 45 suffix-b))
                     {:part (.toByteArray part) :closed? true}

                     :else
                     (do
                       ;; Re-examine every byte except the first marker byte,
                       ;; permitting overlapping candidate boundary prefixes.
                       (.write part (bit-and 0xff (aget marker 0)))
                       (when (not= -1 suffix-b) (.unread in suffix-b))
                       (when (not= -1 suffix-a) (.unread in suffix-a))
                       (.unread in marker 1 (dec size))
                       (recur 0))))
                 (recur (inc matched)))
               (if (zero? matched)
                 (do (.write part b) (recur 0))
                 (do
                   (.write part (bit-and 0xff (aget marker 0)))
                   (.unread in b)
                   (.unread in marker 1 (dec matched))
                   (recur 0))))))))

     (defn- check-multipart-end
       "After the terminal boundary, allow only trailing CRLF or LF."
       [^PushbackInputStream in]
       (loop [b (.read in)]
         (cond
           (= -1 b) nil
           (= 10 b) (recur (.read in))
           (= 13 b) (do
                      (assert-valid (= 10 (.read in))
                                    "Invalid data after terminal multipart boundary"
                                    ::invalid-multipart-epilogue)
                      (recur (.read in)))
           :else
           (assert-valid false
                         "Invalid data after terminal multipart boundary"
                         ::invalid-multipart-epilogue))))))

(defn parse-parts [#?(:clj ^InputStream in
                      :cljs ^String in)
                   ^String boundary]
  (try
    #?(:clj
       (let [marker (.getBytes (str "\r\n--" boundary) "US-ASCII")
             opening (.getBytes (str "--" boundary "\r\n") "US-ASCII")]
         (with-open [^PushbackInputStream stream
                     (PushbackInputStream. (BufferedInputStream. in) (+ 2 (alength marker)))]
           ;; A leading CRLF is optional. A nonempty preamble is not.
           (let [first-byte (.read stream)]
             (if (= 13 first-byte)
               (assert-valid (= 10 (.read stream))
                             "Invalid multipart preamble"
                             ::invalid-multipart-preamble)
               (do
                 (assert-valid (not= -1 first-byte)
                               "Missing multipart opening boundary"
                               ::invalid-multipart-boundary)
                 (.unread stream first-byte))))
           (doseq [opening-byte opening]
             (assert-valid (= (bit-and 0xff opening-byte) (.read stream))
                           "Invalid multipart opening boundary"
                           ::invalid-multipart-boundary))
           (loop [parts []]
             (let [{:keys [part closed?]} (read-multipart-part stream marker)
                   parsed-part (parse-part-bytes part)
                   next-parts (conj parts parsed-part)]
               (if closed?
                 (do (check-multipart-end stream) next-parts)
                 (recur next-parts))))))
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
