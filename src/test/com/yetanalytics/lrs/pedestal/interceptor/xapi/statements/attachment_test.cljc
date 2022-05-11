(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment-test
  (:require [clojure.test :refer [deftest testing is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment
             :as attachment
             :refer [decode-sig
                     validate-sig
                     validate-statements-multiparts]])
  #?(:clj (:import [java.io ByteArrayInputStream])))

(deftest sig-test
  (let [att-obj {"usageType" "http://adlnet.gov/expapi/attachments/signature",
                 "display" {"en-US" "Signed by the Test Suite"},
                 "description" {"en-US" "Signed by the Test Suite"},
                 "contentType" "application/octet-stream",
                 "length" 796,
                 "sha2"
                 "f7db3634a22ea2fe4de1fc519751046a3bdf1e5605a316a19343109bd6daa388"}
        statement {"actor"
                   {"objectType" "Agent",
                    "name" "xAPI mbox",
                    "mbox" "mailto:xapi@adlnet.gov"},
                   "verb"
                   {"id" "http://adlnet.gov/expapi/verbs/attended",
                    "display" {"en-GB" "attended", "en-US" "attended"}},
                   "object"
                   {"objectType" "Activity",
                    "id" "http://www.example.com/meetings/occurances/34534"},
                   "id" "2e2f1ad7-8d10-4c73-ae6e-2842729e25ce",
                   "attachments"
                   [att-obj]}
        sig "eyJhbGciOiJSUzI1NiJ9.eyJhY3RvciI6eyJvYmplY3RUeXBlIjoiQWdlbnQiLCJuYW1lIjoieEFQSSBtYm94IiwibWJveCI6Im1haWx0bzp4YXBpQGFkbG5ldC5nb3YifSwidmVyYiI6eyJpZCI6Imh0dHA6Ly9hZGxuZXQuZ292L2V4cGFwaS92ZXJicy9hdHRlbmRlZCIsImRpc3BsYXkiOnsiZW4tR0IiOiJhdHRlbmRlZCIsImVuLVVTIjoiYXR0ZW5kZWQifX0sIm9iamVjdCI6eyJvYmplY3RUeXBlIjoiQWN0aXZpdHkiLCJpZCI6Imh0dHA6Ly93d3cuZXhhbXBsZS5jb20vbWVldGluZ3Mvb2NjdXJhbmNlcy8zNDUzNCJ9LCJpZCI6IjJlMmYxYWQ3LThkMTAtNGM3My1hZTZlLTI4NDI3MjllMjVjZSJ9.roBpi7viDC4DyNikcWtjuvfXEfrVqNtukVfOjoj-VEGbskcxc9H21GKQBsw3LxnpblIpiDPithCs2AOZK7RFy4vB9wsL5HmX8jpxGvGnYCWNEbVRGoYyntFWjF3wFtTaJMHvZLnirL6k1qhxdfJPcV2C-uc-FXC9AR4__xYbJioJDb37wvPtetD8x8YTdkMkM7nlv20GjV3YF-wa_cxt9hWVS-8LDikCswY6PpMLFR6eYeqIqrZxJQtqDhsZK3k28eHDxAnNB-dGoYeiSeFSbcToyVh4iz2lZGNUmfkltiVs7mLTVJNilU0Z41JIFrdYEGXEfYQwFmiIf5denL5_lg"
        multipart {:content-type "application/octet-stream"
                   :content-length 796
                   :headers {"Content-Type"              "application/octet-stream"
                             "Content-Transfer-Encoding" "binary"
                             "X-Experience-API-Hash"     "f7db3634a22ea2fe4de1fc519751046a3bdf1e5605a316a19343109bd6daa388"}}]
    (testing "decode-sig"
      (= (dissoc statement "attachments")
         (decode-sig sig)))
    (testing "valid-sig"
      (is
       (= sig
          (-> (validate-sig
               statement
               att-obj
               (assoc multipart
                      :input-stream
                      #?(:clj (ByteArrayInputStream.
                               (.getBytes sig "UTF-8"))
                         :cljs sig)))
              :input-stream
              slurp))))
    (testing "invalid-sig"
      (let [invalid-sig (apply str (drop 10 sig))]
        (is (= ::attachment/invalid-signature-json
               (try
                 (validate-sig
                  statement
                  att-obj
                  (assoc multipart
                         :input-stream
                         #?(:clj (ByteArrayInputStream.
                                  (.getBytes invalid-sig "UTF-8"))
                            :cljs invalid-sig)))
                 (catch #?(:clj clojure.lang.ExceptionInfo
                           :cljs ExceptionInfo) exi
                   (-> exi ex-data :type)))))))))

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
                      "sha2"        "7e0c4bbe6280e85cf8525dd7afe8d6ffe9051fbc5fadff71d4aded1ba4c74b53"}])]]
        (testing "works with a dup multipart"
          (is (= [statements [multipart
                              multipart]]
                 (validate-statements-multiparts
                  statements
                  [multipart
                   multipart]))))
        #_(testing "works with a dedup multipart"
            (is (= [statements [multipart]]
                   (validate-statements-multiparts
                    statements
                    [multipart
                     multipart]))))
        (testing "fails with a single multipart"
          (is (= ::attachment/invalid-multipart-format
                 (try (validate-statements-multiparts
                       statements
                       [multipart])
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs ExceptionInfo) exi
                        (-> exi ex-data :type))))))))))
