(ns com.yetanalytics.lrs-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.test-support :as support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.impl.memory :as mem]
            [com.yetanalytics.lrs :refer :all]

            [com.yetanalytics.datasim.input :as sim-input]
            [com.yetanalytics.datasim.sim :as sim]
            [com.yetanalytics.datasim.util.sequence :as su]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as t]))

(alias 'stc 'clojure.spec.test.check)

(deftest-check-ns ns-test com.yetanalytics.lrs
  {:default {::stc/opts {:num-tests 5 :max-size 3}}})

;; Let's base tests on data from Datasim if we're not sure about the serialized
;; internal state
(def test-statements
  "test statements for datasim, lazy"
  (-> (sim-input/from-location
       :input :json "dev-resources/datasim/input/tc3.json")

      sim/build-skeleton
      ;; TODO: use sim-seq when merged https://github.com/yetanalytics/datasim/pull/33
      vals
      (->> (su/seq-sort (comp :timestamp-ms meta))
           (take 100))))




(deftest query-test
  (let [auth-id {:auth :com.yetanalytics.lrs.auth/no-op
                 :prefix ""
                 :scopes #{:scope/all}
                 :agent {"mbox" "mailto:lrs@yetanalytics.io"}}]
    ;; the serialized state shouldn't be used for this, because we're changing
    ;; things around.
    ;; the datasim data is not currently normalized, but only happens at a max
    ;; precision of 1 ms. That's timestamps though, looks like we have no
    ;; normalization going on
    (let [s-count 100
          lrs (doto (mem/new-lrs {:statements-result-max s-count})
                (store-statements auth-id
                                  (into [] (take s-count)
                                        test-statements)
                                  []))
          get-ss #(into []
                        (get-in (get-statements lrs auth-id % #{"en-us"})
                                [:statement-result :statements]))
          ret-statements (get-ss {})
          ]

      (testing (format "%s valid return statements?" (count ret-statements))
        (is (s/valid? (s/every ::xs/statement)
                      ret-statements)))
      (testing "preserved?"
        (is (= (dissoc (first ret-statements)
                       "authority"
                       "version"
                       "stored")
               (dissoc (last test-statements)
                       "authority"
                       "version"
                       "stored"))))
      (testing "default ordering"
        (let [stored-insts (map (comp t/parse
                                      #(get % "stored"))
                                ret-statements)]
          (is (apply distinct? stored-insts))
          (is (= stored-insts
                 (sort (comp #(* % -1) compare)
                       stored-insts)))))

      (testing "default limit"
        (= 100 (count ret-statements)))

      (testing "ascending"
        (= ret-statements
           (reverse (get-ss {:ascending true}))))
      (testing "since + until"
        (let [fstored (-> ret-statements
                          last
                          (get "stored"))
              lstored (-> ret-statements
                          first
                          (get "stored"))]
          (testing "both"
            (is (= 99
                   (count (get-ss {:since fstored
                                   :until lstored
                                   :limit 100}))))
            (testing "ascending"
              (is (= 99
                     (count (get-ss {:ascending true
                                     :since fstored
                                     :until lstored
                                     :limit 100}))))))
          (testing "just since"
            (is (= 99
                   (count (get-ss {:since fstored
                                   :limit 100}))))
            (testing "ascending"
              (is (= 99
                     (count (get-ss {:ascending true
                                     :since fstored
                                     :limit 100}))))))
          (testing "just until"
            (is (= 100
                   (count (get-ss {:until lstored
                                   :limit 100}))))
            (testing "ascending"
              (is (= 100
                     (count (get-ss {:ascending true
                                     :until lstored
                                     :limit 100})))))))))))
