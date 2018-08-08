(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment
  "Provide validation and temp storage for statement attachments."
  (:require [clojure.spec.alpha :as s :include-macros true]
            [clojure.spec.gen.alpha :as sgen :include-macros true]
            [xapi-schema.spec :as xs]
            [clojure.string :as cs]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            #?@(:clj [[clojure.java.io :as io]
                      [cheshire.core :as json]]
                :cljs [cljs.nodejs
                       fs tmp
                       [goog.crypt :as crypt]
                       [goog.crypt.base64 :as base64]]))
  #?(:clj (:import [java.io
                    File
                    InputStream
                    ByteArrayInputStream
                    ByteArrayOutputStream
                    IOException]
                   [java.util Base64])))


;; TODO: tmp externs
#?(:clj (set! *warn-on-reflection* true))

(s/def :xapi.statements/attachments
  (s/coll-of ::attachment :gen-max 10))


(defn close-multiparts!
  "Close all input streams in a sequence of multiparts"
  [multiparts]
  #?(:clj (doseq [{:keys [^InputStream input-stream]} multiparts]
            (.close input-stream))
     :cljs nil))

#?(:clj (defn save-attachment
          "Given a multipart, save it to storage and return an attachment"
          [{:keys [content-type
                   content-length
                   ^InputStream input-stream
                   headers]}
           & [^File tempdir]]
          (let [sha2 (get headers "X-Experience-API-Hash")
                prefix "xapi_attachment_"
                suffix (str "_" sha2)
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
            {:sha2 sha2
             :contentType content-type
             :length (.length tempfile)
             :content tempfile}))
   :cljs (defn save-attachment
          "Given a multipart, save it to storage and return an attachment"
          [{:keys [content-type
                   content-length
                   ^String input-stream
                   headers]}
           & [tempdir]
           ]
          (let [sha2 (get headers "X-Experience-API-Hash")
                prefix "xapi_attachment_"
                suffix (str "_" sha2)
                tempfile (.fileSync tmp (if tempdir
                                          #js {:dir tempdir
                                               :prefix prefix
                                               :postfix suffix}
                                          #js {:prefix prefix
                                               :postfix suffix}))
                ]
            (try (.writeFileSync fs (.-name tempfile) input-stream)
                 (catch js/Error e
                   (throw (ex-info "Attachment Tempfile Save Failed!"
                                   {:type ::attachment-save-failure}
                                   e))))
            {:sha2 sha2
             :contentType content-type
             :length (.-length input-stream)
             :content tempfile})))

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
    (cons statement
          (concat attachments
                  (get object "attachments")))))

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
(defn decode-sig-json [^String part kw-keys?]
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
  (let [[headers payload sig :as parts] (cs/split jws #"\.")]
    {:headers (decode-sig-json headers true)
     :payload (decode-sig-json payload false)}))

(defn validate-sig
  "Validate a signed statement against a multipart and return if valid."
  [statement {:keys [#?(:clj ^InputStream input-stream
                        :cljs ^String input-stream)] :as multipart}]
  (let [^String jws #?(:clj (with-open [in input-stream]
                              (slurp in :encoding "UTF-8"))
                       :cljs input-stream)
        {:keys [headers payload]} (decode-sig
                                   jws)
        {:keys [alg typ]} headers]
    (cond
      (not (#{"RS256" "RS384" "RS512"}
            alg))
      (throw (ex-info "JWS Algorithm MUST be RS256, RS384, or RS512"
                      {:type ::invalid-signature-alg
                       :jws jws
                       :statement statement}))
      (not= (dissoc statement
                    "attachments")
            payload)
      (throw (ex-info "Statement signature does not match statement"
              {:type ::invalid-signature-mismatch
               :jws jws
               :statement statement}))
      :else
      ;; If everything is all good, return the multipart with a new input stream
      (assoc multipart
             :input-stream #?(:clj (ByteArrayInputStream. (.getBytes jws "UTF-8"))
                              :cljs jws)))))


(defn multipart-map
  "Given a list of multiparts, make a map of them by xapi hash"
  [multiparts]
  (reduce (fn [m {:keys [headers] :as multipart}]
            (assoc m
                   (get headers "X-Experience-API-Hash")
                   multipart))
          {}
          multiparts))

(defn sig?
  "Predicate, returns true if the given attachment object is a signature"
  [attachment-object]
  (= "http://adlnet.gov/expapi/attachments/signature"
     (get attachment-object "usageType")))

(defn validate-statements-multiparts
  "Validate and return statements and their multipart attachments"
  [statements multiparts]
  (let [;; collect the attachments per statement
        ;; and reduce over them
        {valid-statements :s-acc
         valid-multiparts :a-acc
         leftover-multiparts :mps}
        (reduce
         (fn [{:keys [mps] :as m}
              [s & aos]]
           ;; match the attachment objects to a multipart
           ;; or assert that they are a fileURL
           (let [valid-matched
                 (keep
                  (fn [ao]
                    ;; If we match..
                    (if-let [[sha mp]
                             (find mps (get ao "sha2"))]
                      ;; if it's a sig
                      (if (sig? ao)
                        (if (= (:content-type mp)
                               (get ao "contentType")
                               "application/octet-stream")
                          ;; if the ctype is valid, validate the sig
                          [sha (validate-sig s mp)]
                          ;; if that ctype was wrong, throw
                          (throw (ex-info
                                  "Statement signature attachment contentType must be application/octet-stream"
                                  {:type ::invalid-signature-attachment-content-type
                                   :attachment-object ao
                                   :attachment-multipart mp})))
                        ;; If not, return the sha and multipart
                        [sha mp])
                      ;; If we don't, this better be a fileURL
                      (when-not (get ao "fileUrl")
                        (throw (ex-info "Invalid multipart format"
                                        {:type ::invalid-multipart-format})))))
                  aos)
                 valid-shas (map first valid-matched)]
             (-> m
                 (update :s-acc conj s)
                 (update :a-acc into (map second valid-matched))
                 (assoc :mps
                        (reduce dissoc mps valid-shas)))))
         {;; accumulators for the
          ;; statements + attachments
          :s-acc []
          :a-acc []
          ;; the multiparts to join up,
          ;; by Hash
          :mps (multipart-map multiparts)}
         (statements-attachments statements))
        ]
    (if (seq leftover-multiparts)
      ;; if we have leftovers, it's bad
      (throw (ex-info "Attachment sha2s differ from statement sha2s"
                      {:type ::statement-attachment-mismatch
                       :leftover-multiparts (into [] leftover-multiparts)}))
      ;; if not, let's return the statements and multiparts
      [valid-statements valid-multiparts])))


(comment

  (def s-json "{\"actor\":{\"objectType\":\"Agent\",\"name\":\"xAPI mbox\",\"mbox\":\"mailto:xapi@adlnet.gov\"},\"verb\":{\"id\":\"http://adlnet.gov/expapi/verbs/attended\",\"display\":{\"en-GB\":\"attended\",\"en-US\":\"attended\"}},\"object\":{\"objectType\":\"Activity\",\"id\":\"http://www.example.com/meetings/occurances/34534\"},\"id\":\"2e2f1ad7-8d10-4c73-ae6e-2842729e25ce\",\"attachments\":[{\"usageType\":\"http://adlnet.gov/expapi/attachments/signature\",\"display\":{\"en-US\":\"Signed by the Test Suite\"},\"description\":{\"en-US\":\"Signed by the Test Suite\"},\"contentType\":\"application/octet-stream\",\"length\":796,\"sha2\":\"f7db3634a22ea2fe4de1fc519751046a3bdf1e5605a316a19343109bd6daa388\"}]}")

  (def sig "eyJhbGciOiJSUzI1NiJ9.eyJhY3RvciI6eyJvYmplY3RUeXBlIjoiQWdlbnQiLCJuYW1lIjoieEFQSSBtYm94IiwibWJveCI6Im1haWx0bzp4YXBpQGFkbG5ldC5nb3YifSwidmVyYiI6eyJpZCI6Imh0dHA6Ly9hZGxuZXQuZ292L2V4cGFwaS92ZXJicy9hdHRlbmRlZCIsImRpc3BsYXkiOnsiZW4tR0IiOiJhdHRlbmRlZCIsImVuLVVTIjoiYXR0ZW5kZWQifX0sIm9iamVjdCI6eyJvYmplY3RUeXBlIjoiQWN0aXZpdHkiLCJpZCI6Imh0dHA6Ly93d3cuZXhhbXBsZS5jb20vbWVldGluZ3Mvb2NjdXJhbmNlcy8zNDUzNCJ9LCJpZCI6IjJlMmYxYWQ3LThkMTAtNGM3My1hZTZlLTI4NDI3MjllMjVjZSJ9.roBpi7viDC4DyNikcWtjuvfXEfrVqNtukVfOjoj-VEGbskcxc9H21GKQBsw3LxnpblIpiDPithCs2AOZK7RFy4vB9wsL5HmX8jpxGvGnYCWNEbVRGoYyntFWjF3wFtTaJMHvZLnirL6k1qhxdfJPcV2C-uc-FXC9AR4__xYbJioJDb37wvPtetD8x8YTdkMkM7nlv20GjV3YF-wa_cxt9hWVS-8LDikCswY6PpMLFR6eYeqIqrZxJQtqDhsZK3k28eHDxAnNB-dGoYeiSeFSbcToyVh4iz2lZGNUmfkltiVs7mLTVJNilU0Z41JIFrdYEGXEfYQwFmiIf5denL5_lg")



  (def s-parsed (parse-string s-json))
  (= (dissoc s-parsed "attachments") (:payload (decode-sig sig)))
  (keys s-parsed)

  (validate-sig s-parsed
                {:input-stream sig})


  (validate-sig )
  (= (json/parse-string-strict s-json)
     (json/parse-string s-json))




  )
