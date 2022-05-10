(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment-test
  (:require [clojure.test :refer [deftest testing is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment
             :as attachment
             :refer [validate-statements-multiparts]])
  #?(:clj (:import [java.io ByteArrayInputStream])))

(deftest validate-statements-multiparts-test
  (let [s-template {"id"          "78efaab3-1c65-4cb7-9289-f34e0594b274"
                    "actor"       {"mbox"       "mailto:bob@example.com"
                                   "objectType" "Agent"}
                    "verb"        {"id" "https://example.com/verb"}
                    "timestamp"   "2022-05-04T13:32:10.486195Z"
                    "version"     "1.0.3"
                    "object"      {"id" "https://example.com/activity"}}
        multipart {:content-type "application/octet-stream"
                   :content-length 20
                   :headers {"Content-Type"              "application/octet-stream"
                             "Content-Transfer-Encoding" "binary"
                             "X-Experience-API-Hash"     "7e0c4bbe6280e85cf8525dd7afe8d6ffe9051fbc5fadff71d4aded1ba4c74b53"}
                   :input-stream #?(:clj (ByteArrayInputStream.
                                          (.getBytes "some text\n some more" "UTF-8"))
                                    :cljs "some text\n some more")}]
    (testing "empty"
      (is (= [[] []]
             (validate-statements-multiparts
              []
              []))))

    (testing "simple"
      (let [statements
            [(assoc s-template
                    "attachments"
                    [{"usageType"   "https://example.com/usagetype"
                      "display"     {"en-US" "someattachment"}
                      "contentType" "application/octet-stream"
                      "length"      20
                      "sha2"        "7e0c4bbe6280e85cf8525dd7afe8d6ffe9051fbc5fadff71d4aded1ba4c74b53"}])]
            multiparts
            [multipart]]
        (is (= [statements multiparts]
               (validate-statements-multiparts
                statements
                multiparts)))))
    (testing "dup reference"
      ;; TODO: per this test, the lib currently requires that each referenced
      ;; multipart be provided, even if they share a SHA.
      ;; Is this what we intend?
      (let [statements
            [(assoc s-template
                    "attachments"
                    [{"usageType"   "https://example.com/usagetype"
                      "display"     {"en-US" "someattachment"}
                      "contentType" "application/octet-stream"
                      "length"      20
                      "sha2"        "7e0c4bbe6280e85cf8525dd7afe8d6ffe9051fbc5fadff71d4aded1ba4c74b53"}
                     {"usageType"   "https://example.com/usagetype"
                      "display"     {"en-US" "someattachment"}
                      "contentType" "application/octet-stream"
                      "length"      20
                      "sha2"        "7e0c4bbe6280e85cf8525dd7afe8d6ffe9051fbc5fadff71d4aded1ba4c74b53"}])]
            multiparts
            [multipart
             multipart]]
        (testing "works with a dup multipart"
          (is (= [statements multiparts]
                 (validate-statements-multiparts
                  statements
                  multiparts))))
        (testing "fails with a single multipart"
          (is (= ::attachment/invalid-multipart-format
                 (try (validate-statements-multiparts
                       statements
                       [multipart])
                      (catch clojure.lang.ExceptionInfo exi
                        (-> exi ex-data :type))))))))))
