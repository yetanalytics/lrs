(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
  (:require
   [clojure.core.async :as a :include-macros true]
   [com.yetanalytics.lrs.protocol :as lrsp]
   #?@(:clj [[cheshire.core :as json]]
       :cljs [[goog.string :refer [format]]
              [goog.string.format]])))

;; TODO: Dynamic boundary?
;; TODO: make this async, work on a servlet output stream

(def crlf "\r\n")

(def boundary
  "105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0")

(def content-type
  (format "multipart/mixed; boundary=%s"
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
      (loop [stage   :init
             s-count 0] ; s-count is used with multiple statements to enclose
        (if-let [x (a/<! statement-result-chan)]
          (case x
            ;; terminate immediately on async error
            ::lrsp/async-error nil
            ;; Headers
            :statement
            (recur :statement s-count)
            :statements
            (do (a/>! body-chan statement-result-pre)
                (recur :statements s-count))
            :more
            (do (a/>! body-chan (format "],\"more\":\"%s\"}"
                                        (a/<! statement-result-chan)))
                (recur :more s-count))
            :attachments
            (do
              ;; When there's no more link, close the statement result
              (when (= stage :statements)
                (a/>! body-chan statement-result-post))
              (recur :attachments s-count))
            ;; Now we have a stage, dispatch on that
            (case stage
              :statement
              (do (a/>! body-chan (json-string x))
                  (recur :statement s-count))
              :statements
              (do
                ;; Maybe comma
                (when (< 0 s-count)
                  (a/>! body-chan ","))
                ;; Write statement
                (a/>! body-chan (json-string x))
                (recur :statements (inc s-count)))
              :attachments
              (let [{:keys [content contentType sha2]} x]
                ;; attachment headers
                (a/>! body-chan
                      (str crlf
                           "--"
                           boundary
                           crlf
                           (format "Content-Type:%s" contentType)
                           crlf
                           "Content-Transfer-Encoding:binary"
                           crlf
                           (format "X-Experience-API-Hash:%s" sha2)
                           crlf
                           crlf))
                ;; Attachment Content
                (a/>! body-chan
                      content)
                (recur :attachments s-count))))
          ;; Successful completion end boundary
          (a/>! body-chan (str crlf
                               "--"
                               boundary
                               "--"))))
      ;; Close the body chan
      (a/close! body-chan))
    body-chan))
