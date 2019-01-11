(ns com.yetanalytics.lrs.auth-test
  (:require [clojure.test :refer [deftest is] :include-macros true]
            [clojure.spec.test.alpha :as stest :include-macros true]
            [com.yetanalytics.test-support :refer [failures stc-opts]]
            [com.yetanalytics.lrs.auth :as auth]))

(deftest scope-ancestors-test
  (is (empty?
       (failures
        (stest/check
         `auth/scope-ancestors)))))

(deftest resolve-scopes-test
  (is (empty?
       (failures
        (stest/check
         `auth/resolve-scopes)))))
