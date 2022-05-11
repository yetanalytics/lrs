(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment
  "Provide validation and temp storage for statement attachments."
  (:require [clojure.spec.alpha :as s :include-macros true]
            [clojure.string :as cs]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            #?@(:clj [[clojure.java.io :as io]
                      [cheshire.core :as json]]
                :cljs [[cljs.nodejs]
                       [fs]
                       [tmp]
                       [goog.crypt]
                       [goog.crypt.base64 :as base64]]))
  #?(:clj (:import [java.io
                    File
                    InputStream
                    ByteArrayInputStream
                    IOException]
                   [java.util Base64])))

(s/def :xapi.statements/attachments
  (s/coll-of ::attachment :gen-max 10))

#_{:clj-kondo/ignore [:unused-binding]} ; binding only used in clj
(defn close-multiparts!
  "Close all input streams in a sequence of multiparts"
  [multiparts]
  #?(:clj (doseq [{:keys [^InputStream input-stream]} multiparts]
            (.close input-stream))
     :cljs nil))

#?(:clj
   (defn save-attachment
     "Given a multipart, save it to storage and return an attachment"
     [{:keys [content-type
              ^InputStream input-stream
              headers]}
      & [^File tempdir]]
     (let [sha2     (get headers "X-Experience-API-Hash")
           prefix   "xapi_attachment_"
           suffix   (str "_" sha2)
           tempfile (doto (if tempdir
                            (File/createTempFile prefix suffix tempdir)
                            (File/createTempFile prefix suffix))
                      .deleteOnExit)]
       (try (with-open [in input-stream]
              (io/copy in tempfile))
            (catch IOException ioe
              (throw (ex-info "Attachment Tempfile Save Failed!"
                              {:type ::attachment-save-failure}
                              ioe))))
       {:sha2        sha2
        :contentType content-type
        :length      (.length tempfile)
        :content     tempfile}))
   :cljs
   (defn save-attachment
     "Given a multipart, save it to storage and return an attachment"
     [{:keys [content-type
              ^String input-stream
              headers]}
      & [tempdir]]
     (let [sha2     (get headers "X-Experience-API-Hash")
           prefix   "xapi_attachment_"
           suffix   (str "_" sha2)
           tempfile (.fileSync tmp (if tempdir
                                     #js {:dir     tempdir
                                          :prefix  prefix
                                          :postfix suffix}
                                     #js {:prefix  prefix
                                          :postfix suffix}))]
       (try (.writeFileSync fs
                            (.-name tempfile)
                            input-stream)
            (catch js/Error e
              (throw (ex-info "Attachment Tempfile Save Failed!"
                              {:type ::attachment-save-failure}
                              e))))
       {:sha2        sha2
        :contentType content-type
        :length      (.-length input-stream)
        :content     tempfile})))

(defn save-attachments
  "Save a list of multiparts and return attachments"
  [multiparts]
  (mapv save-attachment multiparts))

(defn statements-attachments
  "For each statement, get any attachment objects,
   and make of them a seq of seqs."
  [statements]
  (for [{:strs [attachments
                object]
         :as statement} statements]
    (->> (get object "attachments")
         (concat attachments)
         (cons statement))))

(defn delete-attachments!
  "Delete all tempfiles for a sequence of attachments"
  [attachments]
  (doseq [{:keys [#?(:clj ^File content
                     :cljs content)]} attachments]
    #?(:clj (.delete content)
       :cljs (.removeCallback content))))

(defn parse-string
  [^String s & [kw-keys?]]
  #?(:clj (json/parse-string-strict s (or kw-keys?
                                          false))
     :cljs (js->clj (.parse js/JSON s)
                    :keywordize-keys (or kw-keys?
                                         false))))

;; Signature validation
(defn decode-sig-json
  [^String part kw-keys?]
  (try (parse-string
        #?(:clj (slurp (.decode (Base64/getDecoder) (.getBytes part "UTF-8")))
           :cljs (base64/decodeString part))
        (or kw-keys?
            false))
       (catch #?(:clj com.fasterxml.jackson.core.JsonParseException
                 :cljs js/Error) _
         (throw (ex-info "Invalid Statement Signature JSON"
                         {:type ::invalid-signature-json
                          :part part})))))

(defn decode-sig [^String jws]
  (let [[headers payload _sig] (cs/split jws #"\.")]
    {:headers (decode-sig-json headers true)
     :payload (decode-sig-json payload false)}))

(defn validate-sig
  "Validate a signed statement against a multipart and return if valid."
  [statement
   attachment-object
   {:keys [#?(:clj ^InputStream input-stream
              :cljs ^String input-stream)] :as multipart}]
  (if-not (= (:content-type multipart)
             (get attachment-object "contentType")
             "application/octet-stream")
    (throw
     (ex-info
      "Statement signature attachment contentType must be application/octet-stream"
      {:type                 ::invalid-signature-attachment-content-type
       :attachment-object    attachment-object
       :attachment-multipart multipart}))
    (let [^String jws
          #?(:clj (with-open [in input-stream]
                    (slurp in :encoding "UTF-8"))
             :cljs input-stream)
          {:keys [headers payload]}
          (decode-sig jws)
          {:keys [alg _typ]}
          headers]
      (cond
        (not (#{"RS256" "RS384" "RS512"}
              alg))
        (throw (ex-info "JWS Algorithm MUST be RS256, RS384, or RS512"
                        {:type      ::invalid-signature-alg
                         :jws       jws
                         :statement statement}))
        (not (ss/statements-immut-equal?
              (dissoc statement "attachments")
              payload))
        (throw (ex-info "Statement signature does not match statement"
                        {:type      ::invalid-signature-mismatch
                         :jws       jws
                         :statement statement}))
        :else ; If everything's good, return the multipart w/ a new input stream
        (assoc multipart
               :input-stream
               #?(:clj (ByteArrayInputStream. (.getBytes jws "UTF-8"))
                  :cljs jws))))))

(defn multipart-map
  "Given a list of multiparts, make a map of vectors of them by xapi hash"
  [multiparts]
  (reduce (fn [m {:keys [headers] :as multipart}]
            (update m
                    (get headers "X-Experience-API-Hash")
                    (fnil conj [])
                    multipart))
          {}
          multiparts))

(defn sig?
  "Predicate, returns true if the given attachment object is a signature"
  [attachment-object]
  (= "http://adlnet.gov/expapi/attachments/signature"
     (get attachment-object "usageType")))

(defn make-sha-multipart-pair
  "Given the attachment object `att-obj`, return a pair of its SHA and the
   multipart, or `nil` if `att-obj` is a fileURL."
  [statement multi-parts att-obj]
  ;; If we match...
  (if-let [[sha mps] (find multi-parts (get att-obj "sha2"))]
    (let [mp (first mps)]
      ;; If it's a signature...
      (if (sig? att-obj)
        [sha (validate-sig statement att-obj mp)]
        ;; If not, return the sha and multipart
        [sha mp]))
    ;; If we don't, this better be a fileURL
    (when-not (get att-obj "fileUrl")
      (throw (ex-info "Invalid multipart format"
                      {:type ::invalid-multipart-format})))))

(defn validate-statements-multiparts
  "Validate and return statements and their multipart attachments"
  [statements multiparts]
  (let [;; collect the attachments per statement and reduce over them
        {valid-statements    :s-acc
         valid-multiparts    :a-acc
         leftover-multiparts :mps}
        (reduce
         (fn [{:keys [mps] :as m}
              [s & att-objs]]
           ;; match the attachment objects to a multipart
           ;; or assert that they are a fileURL
           (let [;; Reduce over multiparts and find relevant attachments
                 [next-mps
                  valid-matched]
                 (reduce
                  (fn [[mps' acc :as state] ao]
                    (if-let [[sha mp :as match]
                             (make-sha-multipart-pair
                              s mps' ao)]
                      [(if (< 1 (count (get mps' sha)))
                         (update mps' sha #(into [] (rest %)))
                         (dissoc mps' sha))
                       (conj acc match)]
                      state))
                  [mps []]
                  att-objs)

                 valid-shas    (map first valid-matched)]
             (-> m
                 (update :s-acc conj s)
                 (update :a-acc into (map second valid-matched))
                 (assoc :mps
                        next-mps))))
         {;; accumulators for the statements + attachments
          :s-acc []
          :a-acc []
          ;; the multiparts to join up, by Hash
          :mps   (multipart-map multiparts)}
         (statements-attachments statements))]
    (if (seq leftover-multiparts)
      ;; If we have leftovers, it's bad
      (throw (ex-info "Attachment sha2s differ from statement sha2s"
                      {:type                ::statement-attachment-mismatch
                       :leftover-multiparts (into [] leftover-multiparts)}))
      ;; If not, let's return the statements and multiparts
      [valid-statements valid-multiparts])))
