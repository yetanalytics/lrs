(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response-test
  (:require [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :refer [build-multipart-async]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as a]
            [com.yetanalytics.test-support :as sup]))

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
                   "length"      10
                   "sha2"        "01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca"}]})

(def content
  #?(:clj (.getBytes "some text\n some more" "UTF-8")
     :cljs "some text\n some more"))

(def attachment
  {:content     content,
   :contentType "text/plain",
   :length      20,
   :sha2        "01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca"})

(deftest build-multipart-async-single-test
  (sup/test-async
   (a/go
     (is
      (= ["--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0\r\nContent-Type:application/json\r\n\r\n"
   "{\"object\":{\"id\":\"https://example.com/activity\"},\"authority\":{\"account\":{\"homePage\":\"https://example.com\",\"name\":\"someaccount\"},\"objectType\":\"Agent\"},\"verb\":{\"id\":\"https://example.com/verb\"},\"id\":\"78efaab3-1c65-4cb7-9289-f34e0594b274\",\"timestamp\":\"2022-05-04T13:32:10.486195Z\",\"version\":\"1.0.3\",\"stored\":\"2022-05-04T13:32:10.486195Z\",\"attachments\":[{\"usageType\":\"https://example.com/usagetype\",\"display\":{\"en-US\":\"someattachment\"},\"contentType\":\"application/octet-stream\",\"length\":10,\"sha2\":\"01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca\"}],\"actor\":{\"mbox\":\"mailto:bob@example.com\",\"objectType\":\"Agent\"}}"
   "\r\n--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0\r\nContent-Type:text/plain\r\nContent-Transfer-Encoding:binary\r\nX-Experience-API-Hash:01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca\r\n\r\n"
   #?(:clj (vec content)
      :cljs "some text\n some more"),
   "\r\n--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0--"]
         (-> (build-multipart-async
              (a/to-chan [:statement
                          statement-with-attachment
                          :attachments
                          attachment]))
             (->> (a/into []))
             a/<!
             ;; In clojure, vec the byte arrays for comparison
             #?(:clj (->> (map (fn [x] (if (bytes? x) (vec x) x)))))))))))

(deftest build-multipart-async-multiple-test
  (sup/test-async
   (a/go
     (is
      (= ["--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0\r\nContent-Type:application/json\r\n\r\n"
          "{\"statements\":["
          "{\"object\":{\"id\":\"https://example.com/activity\"},\"authority\":{\"account\":{\"homePage\":\"https://example.com\",\"name\":\"someaccount\"},\"objectType\":\"Agent\"},\"verb\":{\"id\":\"https://example.com/verb\"},\"id\":\"78efaab3-1c65-4cb7-9289-f34e0594b274\",\"timestamp\":\"2022-05-04T13:32:10.486195Z\",\"version\":\"1.0.3\",\"stored\":\"2022-05-04T13:32:10.486195Z\",\"attachments\":[{\"usageType\":\"https://example.com/usagetype\",\"display\":{\"en-US\":\"someattachment\"},\"contentType\":\"application/octet-stream\",\"length\":10,\"sha2\":\"01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca\"}],\"actor\":{\"mbox\":\"mailto:bob@example.com\",\"objectType\":\"Agent\"}}"
          "]}"
          "\r\n--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0\r\nContent-Type:text/plain\r\nContent-Transfer-Encoding:binary\r\nX-Experience-API-Hash:01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca\r\n\r\n"
          (vec content)
          "\r\n--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0--"]
         (-> (build-multipart-async
              (a/to-chan [:statements
                          statement-with-attachment
                          :attachments
                          attachment]))
             (->> (a/into []))
             a/<!
             ;; In clojure, vec the byte arrays for comparison
             #?(:clj (->> (map (fn [x] (if (bytes? x) (vec x) x)))))))))))

(deftest build-multipart-async-multiple-more-test
  (sup/test-async
   (a/go
     (is
      (= ["--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0\r\nContent-Type:application/json\r\n\r\n"
          "{\"statements\":["
          "{\"object\":{\"id\":\"https://example.com/activity\"},\"authority\":{\"account\":{\"homePage\":\"https://example.com\",\"name\":\"someaccount\"},\"objectType\":\"Agent\"},\"verb\":{\"id\":\"https://example.com/verb\"},\"id\":\"78efaab3-1c65-4cb7-9289-f34e0594b274\",\"timestamp\":\"2022-05-04T13:32:10.486195Z\",\"version\":\"1.0.3\",\"stored\":\"2022-05-04T13:32:10.486195Z\",\"attachments\":[{\"usageType\":\"https://example.com/usagetype\",\"display\":{\"en-US\":\"someattachment\"},\"contentType\":\"application/octet-stream\",\"length\":10,\"sha2\":\"01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca\"}],\"actor\":{\"mbox\":\"mailto:bob@example.com\",\"objectType\":\"Agent\"}}"
          "],\"more\":\"https://lrs.example.com/xapi/statements\"}"
          "\r\n--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0\r\nContent-Type:text/plain\r\nContent-Transfer-Encoding:binary\r\nX-Experience-API-Hash:01d448afd928065458cf670b60f5a594d735af0172c8d67f22a81680132681ca\r\n\r\n"
          (vec content)
          "\r\n--105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0--"]
         (-> (build-multipart-async
              (a/to-chan [:statements
                          statement-with-attachment
                          :more
                          "https://lrs.example.com/xapi/statements"
                          :attachments
                          attachment]))
             (->> (a/into []))
             a/<!
             ;; In clojure, vec the byte arrays for comparison
             #?(:clj (->> (map (fn [x] (if (bytes? x) (vec x) x)))))))))))
