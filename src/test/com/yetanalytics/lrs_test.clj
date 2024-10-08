(ns com.yetanalytics.lrs-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.yetanalytics.test-support :as support :refer [deftest-check-ns]]
            [com.yetanalytics.lrs.impl.memory :as mem]
            [com.yetanalytics.lrs :as lrs]
            [clojure.string :as cs]
            [com.yetanalytics.datasim.input :as sim-input]
            [com.yetanalytics.datasim.sim :as sim]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as t]
            [clojure.spec.test.alpha :as stest]))

(alias 'stc 'clojure.spec.test.check)

;; clj-kondo doesn't recognize custom macro
#_{:clj-kondo/ignore [:unresolved-symbol]}
(deftest-check-ns ns-test com.yetanalytics.lrs
  {:default {::stc/opts {:num-tests 5 :max-size 3}}})

;; Let's base tests on data from Datasim if we're not sure about the serialized
;; internal state
(def test-statements
  "test statements for datasim, lazy"
  (take 100 (sim/sim-seq
             (sim-input/from-location
              :input :json "dev-resources/datasim/input/tc3.json"))))

;; instrument LRS api fns throughout tests
(stest/instrument `[lrs/get-about
                    lrs/get-about-async
                    lrs/set-document
                    lrs/set-document-async
                    lrs/get-document
                    lrs/get-document-async
                    lrs/get-document-ids
                    lrs/get-document-ids-async
                    lrs/delete-document
                    lrs/delete-document-async
                    lrs/delete-documents
                    lrs/delete-documents-async
                    lrs/get-activity
                    lrs/get-activity-async
                    lrs/get-person
                    lrs/get-person-async
                    lrs/store-statements
                    lrs/store-statements-async
                    lrs/get-statements
                    lrs/get-statements-async
                    lrs/consistent-through
                    lrs/consistent-through-async
                    lrs/authenticate
                    lrs/authenticate-async
                    lrs/authorize
                    lrs/authorize-async])

(def auth-id
  {:auth   {:no-op {}}
   :prefix ""
   :scopes #{:scope/all}
   :agent  {"mbox" "mailto:lrs@yetanalytics.io"}})

(deftest empty-store-test
  (let [lrs            (doto (mem/new-lrs {:statements-result-max 100})
                         (lrs/store-statements auth-id [] []))
        ret-statements (get-in (lrs/get-statements lrs
                                                   auth-id
                                                   {:limit 100}
                                                   #{"en-US"})
                               [:statement-result :statements])]
    (is (s/valid? ::xs/statements ret-statements))
    (is (empty? ret-statements))))

(deftest query-test
  (let [;; The serialized state shouldn't be used for this, because we're
        ;; changing things around.
        ;; The datasim data is not currently normalized, but only happens at a
        ;; max precision of 1 ms. That's timestamps though, looks like we have
        ;; no normalization going on
        s-count        100
        lrs            (doto (mem/new-lrs {:statements-result-max s-count})
                         (lrs/store-statements auth-id
                                               (into [] (take s-count)
                                                     test-statements)
                                               []))
        get-ss         #(into []
                              (get-in (lrs/get-statements lrs auth-id % #{"en-US"})
                                      [:statement-result :statements]))
        ret-statements (get-ss {:limit 100})]
    (testing (format "%s valid return statements?" (count ret-statements))
      (is (s/valid? ::xs/statements ret-statements)))
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
      (let [stored-insts (map (comp t/parse #(get % "stored"))
                              ret-statements)]
        (is (apply distinct? stored-insts))
        (is (= stored-insts
               (sort (comp #(* % -1) compare)
                     stored-insts)))))

    (testing "default limit"
      (is (= 100 (count ret-statements))))

    (testing "ascending"
      (is (= ret-statements
             (reverse (get-ss {:ascending true
                               :limit     100})))))

    (testing "since + until"
      (let [fstored (-> ret-statements
                        last
                        (get "stored"))
            s-stored (-> ret-statements
                         butlast
                         last
                         (get "stored"))
            lstored (-> ret-statements
                        first
                        (get "stored"))
            s-l-stored (-> ret-statements
                           second
                           (get "stored"))]
        (testing "both"
          (is (= 99
                 (count (get-ss {:since fstored
                                 :until lstored
                                 :limit 100}))))
          (testing "ascending"
            (is (= 99
                   (count (get-ss {:ascending true
                                   :since     fstored
                                   :until     lstored
                                   :limit     100}))))))
        (testing "just since"
          (is (= 99
                 (count (get-ss {:since fstored
                                 :limit 100}))))
          (is (= 98
                 (count (get-ss {:since s-stored
                                 :limit 100}))))
          (testing "ascending"
            (is (= 99
                   (count (get-ss {:ascending true
                                   :since     fstored
                                   :limit     100}))))
            (is (= 98
                   (count (get-ss {:ascending true
                                   :since     s-stored
                                   :limit     100}))))))
        (testing "just until"
          (is (= 100
                 (count (get-ss {:until lstored
                                 :limit 100}))))
          (is (= 99
                 (count (get-ss {:until s-l-stored
                                 :limit 100}))))
          (testing "ascending"
            (is (= 100
                   (count (get-ss {:ascending true
                                   :until     lstored
                                   :limit     100}))))
            (is (= 99
                   (count (get-ss {:ascending true
                                   :until     s-l-stored
                                   :limit     100}))))))))

    (testing "ID params are normalized"
      (let [id (-> ret-statements
                   first
                   (get "id"))]
        (is
         (:statement
          (lrs/get-statements lrs
                              auth-id
                              {:statementId (cs/upper-case id)}
                              #{"en-US"})))))

    (testing "registration param is normalized"
      (let [reg (-> ret-statements
                    first
                    (get-in ["context" "registration"]))]
        (is reg)
        (is
         (not-empty
          (get-in  (lrs/get-statements lrs
                                       auth-id
                                       {:registration (cs/upper-case reg)}
                                       #{"en-US"})
                   [:statement-result :statements])))))

    (testing "ID keys are normalized"
      (let [s   (first test-statements)
            id  (get s "id")
            lrs (doto (mem/new-lrs {:statements-result-max s-count})
                  (lrs/store-statements
                   auth-id
                   [(-> s
                        (update "id" cs/upper-case)
                        (update-in ["context" "registration"] cs/upper-case))]
                   []))]
        (is (:statement (lrs/get-statements
                         lrs
                         auth-id
                         {:statementId id}
                         #{"en-US"})))
        ;; This test will pass even w/o normalized IDs, but it makes sure we
        ;; don't screw up the rel index
        (is (not-empty (get-in (lrs/get-statements
                                lrs
                                auth-id
                                {:verb (get-in s ["verb" "id"])}
                                #{"en-US"})
                               [:statement-result :statements])))

        (testing "reg index"
          (is (not-empty (get-in (lrs/get-statements
                                  lrs
                                  auth-id
                                  {:registration (get-in s ["context"
                                                            "registration"])}
                                  #{"en-US"})
                                 [:statement-result :statements]))))

        (testing "original case is preserved"
          (is (= (cs/upper-case id)
                 (get-in (lrs/get-statements lrs
                                             auth-id
                                             {:statementId id}
                                             #{"en-US"})
                         [:statement "id"]))))))))
