(ns com.yetanalytics.lrs.pedestal.routes.documents-test
  (:require [clojure.test :refer [deftest is testing] :include-macros true]
            [clojure.spec.alpha :as s :include-macros true]
            [com.yetanalytics.lrs.pedestal.routes.documents :as routes]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(def ctx
  {:context-value ::preserved})

(def unexpected-error
  (ex-info "Unexpected document error" {:type ::unexpected}))

(deftest precondition-failed-error-test
  (let [{:keys [error] :as result}
        (doc/precondition-failed-error {:operation :put})]
    (is (doc/precondition-failed? error))
    (is (= {:type      ::doc/precondition-failed
            :operation :put}
           (ex-data error)))
    (is (s/valid? ::p/set-document-ret result))
    (is (s/valid? ::p/delete-document-ret result))
    (is (s/valid? ::p/delete-documents-ret result))))

(deftest put-response-test
  (testing "success"
    (is (= (assoc ctx :response {:status 204})
           (routes/put-response ctx {}))))
  (testing "precondition failure"
    (is (= (assoc ctx :response {:status 412})
           (routes/put-response ctx
                                (doc/precondition-failed-error)))))
  (testing "unexpected implementation error"
    (is (= (assoc ctx :io.pedestal.interceptor.chain/error unexpected-error)
           (routes/put-response ctx {:error unexpected-error})))))

(deftest post-response-test
  (testing "success"
    (is (= (assoc ctx :response {:status 204})
           (routes/post-response ctx {}))))
  (testing "precondition failure"
    (is (= (assoc ctx :response {:status 412})
           (routes/post-response ctx
                                 (doc/precondition-failed-error)))))
  (testing "existing merge errors"
    (doseq [error-type [::doc/json-read-error
                        ::doc/json-not-object-error
                        ::doc/invalid-merge]]
      (is (= (assoc ctx :response {:status 400})
             (routes/post-response
              ctx
              {:error (ex-info "Invalid merge" {:type error-type})})))))
  (testing "unexpected implementation error"
    (is (= (assoc ctx :io.pedestal.interceptor.chain/error unexpected-error)
           (routes/post-response ctx {:error unexpected-error})))))

(deftest delete-response-test
  (testing "success"
    (is (= (assoc ctx :response {:status 204})
           (routes/delete-response ctx {}))))
  (testing "single and multiple document precondition failures"
    (doseq [operation [:delete-document :delete-documents]]
      (is (= (assoc ctx :response {:status 412})
             (routes/delete-response
              ctx
              (doc/precondition-failed-error {:operation operation}))))))
  (testing "unexpected implementation error"
    (is (= (assoc ctx :io.pedestal.interceptor.chain/error unexpected-error)
           (routes/delete-response ctx {:error unexpected-error})))))
