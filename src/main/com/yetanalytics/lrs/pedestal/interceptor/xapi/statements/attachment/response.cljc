(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
  (:require
   [clojure.core.async :as a :include-macros true]
   #?@(:clj [[cheshire.core :as json]]
       :cljs [[goog.string :as gstring]
              [goog.string.format]])))

;; TODO: Dynamic boundary?
;; TODO: make this async, work on a servlet output stream

(def fmt #?(:clj format
            :cljs gstring/format))

(def crlf "\r\n")

(def boundary
  "105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0")

(def content-type
  (fmt "multipart/mixed; boundary=%s"
          boundary))

(def statement-result-pre
  "{\"statements\":[")

(def statement-result-post
  "]}")

(defn json-string [data]
  #?(:clj (json/generate-string data)
     :cljs (.stringify js/JSON (clj->js data))))

(defn build-multipart-async
  [statement-result-chan]
  (let [body-chan (a/chan)]
    (a/go
      ;; Begin
      (a/>! body-chan (str "--"
                           boundary
                           crlf
                           "Content-Type:application/json"
                           crlf
                           crlf))
      (loop [stage :init
             s-count 0] ;; s-count is used with multiple statements to enclose
        (when-let [x (a/<! statement-result-chan)]
          (case x
            ;; headers
            :statement
            (do (a/>! body-chan (json-string (a/<! statement-result-chan)))
                (recur :statement s-count))
            :statements
            (do (a/>! body-chan statement-result-pre)
                (recur :statements s-count))
            :more
            (do (a/>! body-chan (fmt "],\"more\":\"%s\"}"
                                        (a/<! statement-result-chan)))
                (recur :more s-count))
            :attachments
            (do
              ;; when there's no more link, close the statement result
              (when (= stage :statements)
                (a/>! body-chan statement-result-post))
              (recur :attachments s-count))
            ;; now we have a stage, dispatch on that
            (case stage
              :statements
              (do
                ;; maybe Comma
                (when (< 0 s-count)
                  (a/>! body-chan ","))
                ;; Write statement
                (a/>! body-chan (json-string x))
                (recur :statements (inc s-count)))
              :attachments
              (let [{:keys [content contentType sha2] :as attachment-object} x]
                ;; attachment headers
                (a/>! body-chan
                      (str crlf
                           "--"
                           boundary
                           crlf
                           (fmt "Content-Type:%s" contentType)
                           crlf
                           "Content-Transfer-Encoding:binary"
                           crlf
                           (fmt "X-Experience-API-Hash:%s" sha2)
                           crlf
                           crlf))
                ;; Attachment Content
                (a/>! body-chan
                      content)
                (recur :attachments s-count))))))
      ;; End
      (a/>! body-chan (str crlf
                           "--"
                           boundary
                           "--"))
      ;; Close the body chan
      (a/close! body-chan))
    body-chan))
