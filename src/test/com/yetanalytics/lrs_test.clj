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
           #_(map (fn [{:strs [timestamp] :as s}]
                  (assoc s "stored" timestamp)))
           (take 100))))




(deftest since-until-test
  (let [auth-id {:auth :com.yetanalytics.lrs.auth/no-op
                 :prefix ""
                 :scopes #{:scope/all}
                 :agent {"mbox" "mailto:lrs@yetanalytics.io"}}]

    ;; The fixture data is scrubbed and only has ms precision, so all of this would pass
    #_(testing "default fixture thingy"
        (let [lrs (mem/new-lrs {:statements-result-max 100
                                :init-state (mem/fixture-state)})
              ret-statements
              (into []
                    (get-in (get-statements lrs auth-id {} #{"en-US"})
                            [:statement-result :statements]))]
          (testing (format "%s valid return statements" (count ret-statements))
            (is (s/valid? (s/every ::xs/statement)
                          ret-statements)))
          (testing "default ordering"
            (let [stored-insts (map (comp t/parse
                                          #(get % "stored"))
                                    ret-statements)]
              #_(clojure.pprint/pprint stored-insts)
              (is (= stored-insts
                     (sort (comp #(* % -1) compare)
                           stored-insts))
                  )))
          (testing "timestamp normalization"
            (let [stamps (filter string?
                                 (mapcat (juxt #(get % "timestamp")
                                               #(get % "stored"))
                                         ret-statements))

                  by-length (group-by count stamps)]
              (is (and (= 1 (count by-length))
                       (= 24 (-> by-length first key)))
                  (format "non-normalized stamps %s ... %s"
                          (keys (dissoc by-length 24))
                          (into []
                                (take 10
                                      (apply concat
                                             (vals (dissoc by-length 24)))))))))

          ))

    ;; the datasim data is not currently normalized, but only happens at a max
    ;; precision of 1 ms. That's timestamps though, looks like we have no
    ;; normalization going on
    (let [s-count 100
          lrs (doto (mem/new-lrs {:statements-result-max s-count})
                (store-statements auth-id
                                  (into [] (take s-count
                                                 )test-statements)
                                  []))
          ret-statements
          (into []
                (get-in (get-statements lrs auth-id {} #{"en-US"})
                        [:statement-result :statements]))]

      (testing (format "%s valid return statements" (count ret-statements))
        (is (s/valid? (s/every ::xs/statement)
                      ret-statements)))
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
           (reverse (get-in (get-statements lrs
                                            auth-id
                                            {:ascending true}
                                            #{"en-US"})
                            [:statement-result :statements]))))
      (testing "since + until"
        (let [fstored (-> ret-statements
                          last
                          (get "stored"))
              lstored (-> ret-statements
                          first
                          (get "stored"))]
          (testing "both"
            (is (= 99
                   (count (get-in
                           (get-statements lrs
                                           auth-id
                                           {:since fstored
                                            :until lstored
                                            :limit 100}
                                           #{"en-US"})
                           [:statement-result :statements]))))
            (testing "ascending"
              (is (= 99
                     (count (get-in
                             (get-statements lrs
                                             auth-id
                                             {:ascending true
                                              :since fstored
                                              :until lstored
                                              :limit 100}
                                             #{"en-US"})
                             [:statement-result :statements]))))))
          (testing "just since"
            (is (= 99
                   (count (get-in
                           (get-statements lrs
                                           auth-id
                                           {:since fstored
                                            :limit 100}
                                           #{"en-US"})
                           [:statement-result :statements]))))
            (testing "ascending"
              (is (= 99
                     (count (get-in
                             (get-statements lrs
                                             auth-id
                                             {:ascending true
                                              :since fstored
                                              :limit 100}
                                             #{"en-US"})
                             [:statement-result :statements]))))))
          (testing "just until"
            (is (= 100
                   (count (get-in
                           (get-statements lrs
                                           auth-id
                                           {:until lstored
                                            :limit 100}
                                           #{"en-US"})
                           [:statement-result :statements]))))
            (testing "ascending"
              (is (= 100
                     (count (get-in
                             (get-statements lrs
                                             auth-id
                                             {:ascending true
                                              :until lstored
                                              :limit 100}
                                             #{"en-US"})
                             [:statement-result :statements]))))))



          ))

     #_(testing "timestamp normalization"
        (let [tss (filter string?
                          (map #(get % "timestamp")
                               ret-statements))
              sts (filter string?
                          (map #(get % "stored")
                               ret-statements))

              tss-by-length (group-by count tss)
              sts-by-length (group-by count sts)]
          (testing "timestamps"
            (is (and (= 1 (count tss-by-length))
                     (= 24 (-> tss-by-length first key)))
                (format "non-normalized timestamps %s ... %s"
                        (keys (dissoc tss-by-length 24))
                        (into []
                              (take 10
                                    (apply concat
                                           (vals (dissoc tss-by-length 24))))))))
          (testing "storeds"
            (is (and (= 1 (count sts-by-length))
                     (= 24 (-> sts-by-length first key)))
                (format "non-normalized storeds %s ... %s"
                        (keys (dissoc sts-by-length 24))
                        (into []
                              (take 10
                                    (apply concat
                                           (vals (dissoc sts-by-length 24)))))))))))))
