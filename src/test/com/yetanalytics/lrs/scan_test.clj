(ns com.yetanalytics.lrs.scan-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [babashka.curl :as curl]
            [io.pedestal.http :as http]
            [com.yetanalytics.test-support :as support]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def attachment-post-params
  {:basic-auth ["username" "password"]
   :headers
   {"X-Experience-API-Version" "1.0.3"
    "Content-Type"
    "multipart/mixed; boundary=105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0"}
   :body       (slurp "dev-resources/attachments/attachment_post_body.txt")
   :throw      false})

(def doc-post-params
  {:basic-auth ["username" "password"]
   :headers    {"X-Experience-API-Version" "1.0.3"}
   :query-params
   {"activityId" "http://www.example.com/activityId/hashset"
    "agent"
    "{\"objectType\":\"Agent\",\"account\":{\"homePage\":\"http://www.example.com/agentId/1\",\"name\":\"Rick James\"}}"
    "stateId"    "f8128f68-74e2-4951-8c5f-ef7cce73b4ff"}
   :body       "I'm a little teapot"
   :throw      false})

(deftest scan-test
  (testing "File/Document Scanning"
    ;; Stub out a scanner that always fails
    (testing "Failure"
      (let [server (support/test-server
                    :route-opts
                    {:file-scanner
                     (fn [in]
                       (slurp in)
                       {:message "Scan Fail!"})})]
        (try
          (http/start server)
          (testing "Attachment"
            (is (= {:status 400
                    :body   "{\"error\":{\"message\":\"Attachment scan failed, Errors: Scan Fail!\"}}"}
                   (select-keys
                    (curl/post
                     "http://localhost:8080/xapi/statements"
                     attachment-post-params)
                    [:body :status]))))
          (testing "Document"
            (is (= {:status 400
                    :body   "{\"error\":{\"message\":\"Document scan failed, Error: Scan Fail!\"}}"}
                   (select-keys
                    (curl/post
                     "http://localhost:8080/xapi/activities/state"
                     doc-post-params)
                    [:body :status]))))
          (finally
            (http/stop server)))))
    (testing "Success"
      (let [server (support/test-server)]
        (try
          (http/start server)
          (testing "Attachment"
            (is (= 200
                   (:status
                    (curl/post
                     "http://localhost:8080/xapi/statements"
                     attachment-post-params)))))
          (testing "Document"
            (is (= 204
                   (:status
                    (curl/post
                     "http://localhost:8080/xapi/activities/state"
                     doc-post-params)))))
          (finally
            (http/stop server)))))))
