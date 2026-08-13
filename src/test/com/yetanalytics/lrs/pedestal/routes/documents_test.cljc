(ns com.yetanalytics.lrs.pedestal.routes.documents-test
  (:require [clojure.test :refer [deftest is testing] :include-macros true]
            [clojure.core.async :as a :include-macros true]
            [clojure.spec.alpha :as s :include-macros true]
            [com.yetanalytics.test-support :as support]
            [com.yetanalytics.lrs.pedestal.routes.documents :as routes]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(def ctx
  {:context-value ::preserved})

(def unexpected-error
  (ex-info "Unexpected document error" {:type ::unexpected}))

(defn- atomic-sync-lrs
  [enabled?]
  (reify
    p/AtomicDocumentPreconditions
    (-atomic-document-preconditions? [_]
      enabled?)
    p/DocumentResource
    (-set-document [_ _ _ _ _ _] {})
    (-get-document [_ _ _ _]
      (throw (ex-info "Preliminary GET must be skipped" {})))
    (-get-document-ids [_ _ _ _]
      (throw (ex-info "Preliminary GET must be skipped" {})))
    (-delete-document [_ _ _ _] {})
    (-delete-documents [_ _ _ _] {})))

(defn- atomic-async-lrs
  [enabled?]
  (reify
    p/AtomicDocumentPreconditions
    (-atomic-document-preconditions? [_]
      enabled?)
    p/DocumentResourceAsync
    (-set-document-async [_ _ _ _ _ _] (a/go {}))
    (-get-document-async [_ _ _ _]
      (throw (ex-info "Preliminary GET must be skipped" {})))
    (-get-document-ids-async [_ _ _ _]
      (throw (ex-info "Preliminary GET must be skipped" {})))
    (-delete-document-async [_ _ _ _] (a/go {}))
    (-delete-documents-async [_ _ _ _] (a/go {}))))

(deftest atomic-document-preconditions-capability-test
  (testing "unimplemented and disabled capabilities are false"
    (is (false? (p/atomic-document-preconditions? nil)))
    (is (false? (p/atomic-document-preconditions?
                 (atomic-sync-lrs false)))))
  (testing "enabled synchronous and asynchronous capabilities are true"
    (is (true? (p/atomic-document-preconditions?
                (atomic-sync-lrs true))))
    (is (true? (p/atomic-document-preconditions?
                (atomic-async-lrs true))))))

(deftest atomic-etag-precondition-handoff-test
  (let [preconditions {:if-match #{"abc" "def"}
                       :if-none-match :*}
        request       {:headers {"if-match" "\"abc\", \"def\""
                                 "if-none-match" "*"}}
        enter-fn      (fn [ctx]
                        (assoc ctx :response
                               {:preconditions (::doc/preconditions ctx)}))]
    (testing "synchronous implementation skips preliminary GET"
      (let [result ((routes/etags-preproc enter-fn)
                    {:request request
                     :com.yetanalytics/lrs (atomic-sync-lrs true)})]
        (is (= preconditions
               (get-in result [:response :preconditions])))))
    (testing "asynchronous implementation skips preliminary GET"
      (support/test-async
       (a/go
         (let [result (a/<! ((routes/etags-preproc
                              (fn [ctx] (a/go (enter-fn ctx))))
                             {:request request
                              :com.yetanalytics/lrs
                              (atomic-async-lrs true)}))]
           (is (= preconditions
                  (get-in result [:response :preconditions])))))))))

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
