(ns com.yetanalytics.lrs.xapi.statements
  (:require
   [com.yetanalytics.lrs.xapi.agents :as ag]
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.spec.gen.alpha :as sgen :include-macros true]
   [xapi-schema.spec :as xs]
   [com.yetanalytics.lrs.util.hash :refer [sha-256]]
   [clojure.walk :as w]
   #?@(:clj [[clojure.data.priority-map :as pm]
             [clojure.java.io :as io]]))
  #?(:clj (:import [java.time Instant]
                   [clojure.data.priority_map PersistentPriorityMap]
                   [java.io File])))

#?(:clj (set! *warn-on-reflection* true))

(defn statements-priority-map [& key-vals]
  #?(:clj (apply
           pm/priority-map-keyfn-by
           #(get % "stored")
           #(compare %2 %1)
           key-vals)
     :cljs (apply hash-map key-vals)))

(defn s-pm-gen-fn []
  (sgen/return #?(:clj (pm/priority-map-keyfn-by
                        #(get % "stored")
                        #(compare %2 %1))
                  :cljs {})))

(s/def ::statements-priority-map
  #?(:clj (s/with-gen (s/and #(instance? PersistentPriorityMap %)
                             ;; It's a map of statement id to statement
                             (s/map-of :statement/id
                                       ::xs/lrs-statement))
            s-pm-gen-fn)
     :cljs (s/map-of :statement/id
                     ::xs/lrs-statement)))

(s/fdef statements-priority-map
        :args (s/* (s/cat :id :statement/id
                          :statement ::xs/lrs-statement))
        :ret ::statements-priority-map)


(defn now-stamp []
  #?(:clj (str (Instant/now))
     :cljs (.toISOString (js/Date.))))

(s/fdef now-stamp
        :args (s/cat)
        :ret ::xs/timestamp)

(defn fix-context-activities [ca-map]
  (reduce-kv
   (fn [m k v]
     (assoc m k (if (sequential? v)
                  v
                  [v])))
   {}
   ca-map))


(s/fdef fix-context-activities
        :args (s/cat :ca-map
                     (s/alt :xapi-0-95-ca-map
                            (s/map-of #{"parent"
                                        "grouping"
                                        "category"
                                        "other"}
                                      ::xs/activity)
                            :ca-map :context/contextActivities))
        :ret (s/map-of #{"parent"
                         "grouping"
                         "category"
                         "other"}
                       ::xs/context-activities-array))


(defn fix-statement-context-activities [{:strs [context object] :as s}]
  (cond-> s
    (get context "contextActivities")
    (update-in ["context" "contextActivities"] fix-context-activities)

    (get-in object ["context" "contextActivities"])
    (update-in ["object" "context" "contextActivities"] fix-context-activities)))

(s/fdef fix-statement-context-activities
        :args (s/cat :statement ::xs/statement)
        :ret ::xs/statement)

(defn prepare-statement
  "Assign an ID, stored, timestamp, etc prior to storage"
  [{:strs [id timestamp version] :as statement}]
  (let [id (or id (str #?(:clj (java.util.UUID/randomUUID)
                          :cljs (random-uuid))))
        stored (now-stamp)
        timestamp (or timestamp stored)
        authority {"name" "Memory LRS"
                   "objectType" "Agent"
                   "account" {"name" "root"
                              "homePage" "http://localhost:8080"}}]
    (-> statement
        fix-statement-context-activities
        (assoc "id" id
               "stored" stored
               "timestamp" timestamp
               "authority" authority
               "version" "1.0.3"))))

(s/fdef prepare-statement
        :args (s/cat :statement ::xs/statement)
        :ret ::xs/lrs-statement)


(defn dissoc-lrs-attrs [s]
  (dissoc s
          "stored"
          "timestamp"
          "authority"
          "version"))

(s/fdef dissoc-lrs-attrs
        :args (s/cat :statement ::xs/lrs-statement)
        :ret ::xs/statement)

(defn canonize-lmap [lmap ltags]
  (conj {}
        (or (some (fn [ltag]
                    (find lmap ltag))
            ltags)
            (first lmap))))

(s/fdef canonize-lmap
        :args (s/cat :lmap ::xs/language-map
                     :ltags (s/coll-of ::xs/language-tag))
        :ret (s/and ::xs/language-map
                    #(<= (count %) 1)))

(defn format-canonical [s-data ltags]
  (w/prewalk (fn [node]
               (condp #(and
                        (some (partial get node)
                              ["name" "description" "display"])
                        (s/valid? %1 %2)) node
                 ::xs/interaction-component
                 (update node "description" canonize-lmap ltags)
                 :activity/definition
                 (cond-> node
                   (get node "name")
                   (update "name" canonize-lmap ltags)
                   (get node "description")
                   (update "description" canonize-lmap ltags))
                 ::xs/verb
                 (update node "display" canonize-lmap ltags)
                 ::xs/attachment
                 (update node "display" canonize-lmap ltags)
                 node))
             s-data))

(s/fdef format-canonical
        :args (s/cat :statement-data
                     (s/alt :single-statement
                            ::xs/lrs-statement
                            :multiple-statements
                            (s/coll-of ::xs/lrs-statement))
                     :ltags
                     (s/coll-of ::xs/language-tag))
        :ret (s/or :single-statement ::xs/lrs-statement
                   :multiple-statements (s/coll-of ::xs/lrs-statement)))

(defn format-statement-ids [s]
  (-> s
      (update "actor" select-keys ["objectType" "mbox" "mbox_sha1sum" "account" "openid" "member"])
      (update "verb" select-keys ["id"])
      (update "object" select-keys ["objectType"
                                    "id" "mbox" "mbox_sha1sum" "account" "openid" "member"
                                    "actor" "verb" "object" "context" "result" "timestamp"])
      (cond->
          (= "SubStatement" (get-in s ["object" "objectType"]))
        (update "object" format-statement-ids)
        (= "Activity" (get-in s ["object" "objectType"]))
        (update "object" dissoc "objectType"))))

(s/fdef format-statement-ids
        :args (s/cat :s ::xs/lrs-statement)
        :ret ::xs/lrs-statement)

(defn format-ids [ss]
  (map format-statement-ids ss))

(s/fdef format-ids
        :args (s/cat :ss ::xs/lrs-statements)
        :ret (s/coll-of ::xs/lrs-statement))

(defn collect-context-activities [ca-map]
  (for [[_ ca-v] ca-map
        :let [activities (if (sequential? ca-v)
                           ca-v
                           [ca-v])]
        activity activities]
    activity))

(s/fdef collect-context-activities
        :args (s/alt :xapi-0-95-ca-map
                     (s/map-of #{"parent"
                                 "grouping"
                                 "category"
                                 "other"}
                               ::xs/activity)
                     :ca-map :context/contextActivities)
        :ret (s/coll-of ::xs/activity))

(defn statement-related-activities [s]
  (distinct (cond->> (concat
                     (when-let [context-activities (get-in s ["context" "contextActivities"])]
                       (collect-context-activities context-activities))
                     (when-let [ss-context-activities (get-in s ["object" "context" "contextActivities"])]
                       (collect-context-activities ss-context-activities)))
              (= "Activity" (get-in s ["object" "objectType"] "Activity"))
              (cons (get s "object")))))

(s/fdef statement-related-activities
        :args (s/cat :s ::xs/statement)
        :ret (s/coll-of ::xs/activity))

(defn statement-related-activity-ids [s]
  (distinct
   (map #(get % "id")
        (statement-related-activities s))))

(s/fdef statement-related-activity-ids
        :args (s/cat :s ::xs/statement)
        :ret (s/coll-of :activity/id))

(defn statement-agents [{:strs [actor object context authority] :as s}
                        & [broad? agents-only?]]
  (let [broad? (or broad? false)
        agents-only? (or agents-only? false)
        object-type (get object "objectType")]
    (cond->> (distinct
              (concat
               (ag/actor-seq actor)
               (when (#{"Agent" "Group"}
                      object-type)
                 (ag/actor-seq object))
               (when broad?
                 (concat
                  (when authority
                    (ag/actor-seq authority))
                  (when-let [{:strs [instructor team]} context]
                    (concat
                     (when team
                       (ag/actor-seq team))
                     (when instructor
                       (ag/actor-seq instructor))))
                  (lazy-seq
                   (when (= "SubStatement" object-type)
                     (statement-agents
                      object true agents-only?)))))))
      agents-only? (remove #(= "Group" (get % "objectType"))))))

(s/fdef statement-agents
        :args (s/cat :s (s/alt :statement
                               ::xs/statement
                               :sub-statement
                               ::xs/sub-statement)
                     :broad? (s/? boolean?)
                     :agents-only? (s/? boolean?))
        :ret (s/coll-of ::xs/actor))

(defn statements-equal? [& ss]
  (apply = (map dissoc-lrs-attrs ss)))

(defn statement-ref?
  "Predicate, returns true if the object of a statement is a StatementRef"
  [s]
  (or (some-> s
              (get-in ["object" "objectType"])
              (= "StatementRef"))
      false))

(s/fdef statement-ref?
        :args (s/cat :statement ::xs/statement)
        :ret boolean?)


(defn statement-ref-id
  "Return the id of the statement this statement references, if one is present"
  [s]
  (when (statement-ref? s)
    (get-in s ["object" "id"])))

(defn voiding-statement? [s]
  (some-> s (get-in ["verb" "id"]) (= "http://adlnet.gov/expapi/verbs/voided")))

(defn all-attachment-hashes
  "For each statement, get any attachment hashes"
  [statements]
  (distinct
   (keep
    #(get % "sha2")
    (mapcat
     (fn [{:strs [attachments
                  object]
           :as statement}]
       (concat attachments
               (get object "attachments")))
     statements))))

(s/fdef all-attachment-hashes
        :args (s/cat :statements
                     (s/coll-of ::xs/statement))
        :ret (s/coll-of :attachment/sha2))

;; A representation of a stored attachment
;; TODO: generalize
(s/def :attachment/content
  #?(:clj (s/with-gen #(satisfies? clojure.java.io/IOFactory
                                   %)
            sgen/bytes)
     :cljs string?))

(s/def ::attachment
  (s/with-gen (s/keys :req-un [:attachment/content
                               :attachment/contentType
                               :attachment/sha2
                               :attachment/length])
    (fn []
      (sgen/fmap
       (fn [[^bytes content
             content-type]]
         {:content content
          :contentType content-type
          :length (count content)
          :sha2 (sha-256 content)})
       (sgen/tuple
        (s/gen :attachment/content)
        (s/gen :attachment/contentType))))))

(s/def ::attachments
  (s/coll-of ::attachment))
