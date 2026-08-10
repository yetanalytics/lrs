(ns com.yetanalytics.lrs.xapi.document-test
  (:require [clojure.test :refer [deftest is testing] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(deftest parse-etag-preconditions-test
  (testing "no preconditions"
    (is (= {} (doc/parse-etag-preconditions {}))))
  (testing "wildcards"
    (is (= {:if-match      :*
            :if-none-match :*}
           (doc/parse-etag-preconditions
            {"if-match"      "*"
             "if-none-match" "*"}))))
  (testing "ETag sets"
    (is (= {:if-match      #{"abc" "def"}
            :if-none-match #{"ghi" "jkl"}}
           (doc/parse-etag-preconditions
            {"if-match"      "\"abc\", \"def\""
             "if-none-match" "\"ghi\", \"jkl\""})))))

(deftest etag-preconditions-met-test
  (testing "no preconditions"
    (is (doc/etag-preconditions-met? {} {:exists? false}))
    (is (doc/etag-preconditions-met? {} {:exists? true :etag "abc"})))
  (testing "If-Match"
    (is (doc/etag-preconditions-met? {:if-match :*}
                                     {:exists? true :etag "abc"}))
    (is (not (doc/etag-preconditions-met? {:if-match :*}
                                          {:exists? false})))
    (is (doc/etag-preconditions-met? {:if-match #{"abc"}}
                                     {:exists? true :etag "abc"}))
    (is (not (doc/etag-preconditions-met? {:if-match #{"stale"}}
                                          {:exists? true :etag "abc"}))))
  (testing "If-None-Match"
    (is (doc/etag-preconditions-met? {:if-none-match :*}
                                     {:exists? false}))
    (is (not (doc/etag-preconditions-met? {:if-none-match :*}
                                          {:exists? true :etag "abc"})))
    (is (doc/etag-preconditions-met? {:if-none-match #{"stale"}}
                                     {:exists? true :etag "abc"}))
    (is (not (doc/etag-preconditions-met? {:if-none-match #{"abc"}}
                                          {:exists? true :etag "abc"}))))
  (testing "both headers"
    (is (doc/etag-preconditions-met?
         {:if-match #{"abc"} :if-none-match #{"stale"}}
         {:exists? true :etag "abc"}))))

(deftest updated-inst-test
  (is (empty?
       (failures
        (stest/check `doc/updated-inst
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest documents-priority-map-test
  (is (empty?
       (failures
        (stest/check `doc/documents-priority-map
                     {stc-opts {:num-tests 100 :max-size 3}})))))

(deftest merge-or-replace-test
  (is (empty?
       (failures
        (stest/check `doc/merge-or-replace
                     {stc-opts {:num-tests 100 :max-size 3}})))))
