(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response-test
  (:require [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :refer [build-multipart-async
                     json-string]]
            [com.yetanalytics.lrs.protocol :as lrsp]
            [clojure.string :as cs]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as a]
            [com.yetanalytics.test-support :as sup]))

(defn- ->string-body
  [response-v]
  (reduce
   #?(:clj (fn [s x]
             (str s
                  (cond
                    (string? x) x
                    (bytes? x) (slurp x))))
      :cljs str)
   ""
   response-v))

(def content
  #?(:clj (.getBytes "some text\n some more" "UTF-8")
     :cljs "some text\n some more"))

(def attachment-sha2
  "7e0c4bbe6280e85cf8525dd7afe8d6ffe9051fbc5fadff71d4aded1ba4c74b53")

(def attachment-ctype
  "text/plain")

(def attachment
  {:content     content,
   :contentType attachment-ctype,
   :length      20,
   :sha2        attachment-sha2})

(def statement-with-attachment
  {"id"          "78efaab3-1c65-4cb7-9289-f34e0594b274"
   "actor"       {"mbox"       "mailto:bob@example.com"
                  "objectType" "Agent"}
   "verb"        {"id" "https://example.com/verb"}
   "object"      {"id" "https://example.com/activity"}
   "timestamp"   "2022-05-04T13:32:10.486195Z"
   "authority"   {"account"    {"homePage" "https://example.com"
                                "name"     "someaccount"}
                  "objectType" "Agent"}
   "stored"      "2022-05-04T13:32:10.486195Z"
   "version"     "1.0.3"
   "attachments" [{"usageType"   "https://example.com/usagetype"
                   "display"     {"en-US" "someattachment"}
                   "contentType" "application/octet-stream"
                   "length"      20
                   "sha2"        attachment-sha2}]})

(def stmt-json
  (json-string statement-with-attachment))

(def boundary "105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0")

(deftest build-multipart-async-single-test
  (sup/test-async
   (a/go
     (is
      (= (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n")
           stmt-json
           (str "--" boundary)
           (str "Content-Type:" attachment-ctype)
           (str "Content-Transfer-Encoding:binary")
           (str "X-Experience-API-Hash:" attachment-sha2 "\r\n")
           #?(:clj (slurp content)
              :cljs content)
           (str "--" boundary "--")])
         (-> (build-multipart-async
              (a/to-chan! [:statement
                           statement-with-attachment
                           :attachments
                           attachment]))
             (->> (a/into []))
             a/<!
             ->string-body))))))

(deftest build-multipart-async-single-statement-error-test
  (sup/test-async
   (a/go
     (is
      (= (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n\r\n")])
         (-> (build-multipart-async
              (a/to-chan! [:statement
                           ::lrsp/async-error]))
             (->> (a/into []))
             a/<!
             ->string-body))))))

(deftest build-multipart-async-single-attachment-error-test
  (sup/test-async
   (a/go
     (is
      (= (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n")
           stmt-json
           ;; No attachments or closing boundary
           ])
         (-> (build-multipart-async
              (a/to-chan! [:statement
                           statement-with-attachment
                           :attachments
                           ::lrsp/async-error]))
             (->> (a/into []))
             a/<!
             ->string-body))))))

(deftest build-multipart-async-multiple-test
  (sup/test-async
   (a/go
     (is
      (= (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n")
           (str "{\"statements\":[" stmt-json "," stmt-json "]}")
           (str "--" boundary)
           (str "Content-Type:" attachment-ctype)
           (str "Content-Transfer-Encoding:binary")
           (str "X-Experience-API-Hash:" attachment-sha2 "\r\n")
           #?(:clj (slurp content)
              :cljs content)
           (str "--" boundary "--")])
         (-> (build-multipart-async
              (a/to-chan! [:statements
                           statement-with-attachment
                           statement-with-attachment
                           :attachments
                           attachment]))
             (->> (a/into []))
             a/<!
             ->string-body))))))

(deftest build-multipart-async-multiple-statement-error-test
  (sup/test-async
   (a/go
     (is
      (= (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n")
           (str "{\"statements\":[" stmt-json)
           ;; No closing JSON, boundary
           ])
         (-> (build-multipart-async
              (a/to-chan! [:statements
                           statement-with-attachment
                           ::lrsp/async-error]))
             (->> (a/into []))
             a/<!
             ->string-body))))))

(deftest build-multipart-async-multiple-attachment-error-test
  (sup/test-async
   (a/go
     (is
      (= (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n")
           (str "{\"statements\":[" stmt-json "," stmt-json "]}")
           (str "--" boundary)
           (str "Content-Type:" attachment-ctype)
           (str "Content-Transfer-Encoding:binary")
           (str "X-Experience-API-Hash:" attachment-sha2 "\r\n")
           #?(:clj (slurp content)
              :cljs content)
           ;; No closing boundary
           ])
         (-> (build-multipart-async
              (a/to-chan! [:statements
                           statement-with-attachment
                           statement-with-attachment
                           :attachments
                           attachment
                           ::lrsp/async-error]))
             (->> (a/into []))
             a/<!
             ->string-body))))))

(deftest build-multipart-async-multiple-more-test
  (let [more "https://lrs.example.com/xapi/statements"]
    (sup/test-async
     (a/go
       (is
        (=
         (cs/join
          "\r\n"
          [(str "--" boundary)
           (str "Content-Type:application/json\r\n")
           (str "{\"statements\":[" stmt-json "," stmt-json "],\"more\":\"" more "\"}")
           (str "--" boundary)
           (str "Content-Type:" attachment-ctype)
           (str "Content-Transfer-Encoding:binary")
           (str "X-Experience-API-Hash:" attachment-sha2 "\r\n")
           #?(:clj (slurp content)
              :cljs content)
           (str "--" boundary "--")])
         (-> (build-multipart-async
              (a/to-chan! [:statements
                           statement-with-attachment
                           statement-with-attachment
                           :more
                           more
                           :attachments
                           attachment]))
             (->> (a/into []))
             a/<!
             ->string-body)))))))
