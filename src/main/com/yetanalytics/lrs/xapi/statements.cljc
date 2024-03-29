(ns com.yetanalytics.lrs.xapi.statements
  (:require
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.spec.gen.alpha :as sgen :include-macros true]
   [clojure.walk :as w]
   [clojure.string :as cs]
   [com.yetanalytics.lrs.util.hash :refer [sha-256]]
   [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]
   [com.yetanalytics.lrs.xapi.agents :as ag]
   [com.yetanalytics.lrs.xapi.activities :as ac]
   [xapi-schema.spec :as xs]
   [#?(:clj clojure.data.priority-map
       :cljs tailrecursion.priority-map) :as pm]
   #?@(:clj [[clojure.java.io :as io]]
       :cljs [[cljs.nodejs]
              [fs]]))
  #?(:clj (:import [clojure.data.priority_map PersistentPriorityMap])))

(s/fdef normalize-id
  :args (s/cat :id :statement/id)
  :ret :statement/id)

(defn normalize-id
  "Normalize statement IDs"
  #?(:clj ^String [^String id]
     :cljs [id])
  (cs/lower-case id))

(s/fdef get-id
  :args (s/cat :statement ::xs/statement)
  :ret (s/nilable :statement/id))

(defn get-id
  "Return the canonical, normalized ID of this statement if it exists"
  [statement]
  (when-let [id (or (get statement "id")
                    (get statement :statement/id)
                    (get statement :id))]
    (normalize-id id)))

(defn select-statement-keys
  "Filter statment attributes to xapi"
  [statement]
  (select-keys
   statement
   ["id"
    "actor"
    "verb"
    "object"
    "result"
    "context"
    "timestamp"
    "stored"
    "authority"
    "version"
    "attachments"]))

(defn stored-stamp
  [statement]
  ;; TODO: Not sure why we're seeing multiple types here
  (or (get statement "stored")
      (get statement :statement/stored)
      (get statement :stored)
      (throw (ex-info "NO STORED?!?"
                      {:type ::no-stored
                       :statement statement}))))

(s/fdef stored-stamp
  :args (s/cat :statement ::xs/lrs-statement)
  :ret ::xs/timestamp)

(defn stored-inst
  [statement]
  (timestamp/parse
   (stored-stamp statement)))

(s/fdef stored-inst
  :args (s/cat :statement ::xs/lrs-statement)
  :ret inst?)

(defn statements-priority-map [& key-vals]
  (apply
   pm/priority-map-keyfn-by
   stored-stamp
   #(compare %2 %1)
   key-vals))

(defn s-pm-gen-fn []
  (sgen/return (pm/priority-map-keyfn-by
                stored-inst
                #(compare %2 %1))))

(s/def ::statements-priority-map
  (s/with-gen (s/and #(instance? #?(:clj PersistentPriorityMap
                                    :cljs pm/PersistentPriorityMap)
                                 %)
                     ;; It's a map of statement id to statement
                     (s/map-of :statement/id
                               ::xs/lrs-statement))
    s-pm-gen-fn))

(s/fdef statements-priority-map
  :args (s/* (s/cat :id :statement/id
                    :statement ::xs/lrs-statement))
  :ret ::statements-priority-map)


(defn now-stamp []
  (timestamp/stamp-now))

(s/fdef now-stamp
  :args (s/cat)
  :ret ::xs/timestamp)

(defn fix-context-activities [ca-map]
  (reduce-kv
   (fn [m k v]
     (assoc m
            k
            (if (sequential? v) v [v])))
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

(defn fix-statement-context-activities
  [{:strs [context object] :as s}]
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
  [{:strs [id stored timestamp _version] :as statement}]
  (let [id        (or id (str #?(:clj (java.util.UUID/randomUUID)
                                 :cljs (random-uuid))))
        stored    (or stored (now-stamp))
        timestamp (or timestamp stored)
        authority {"name"       "Memory LRS"
                   "objectType" "Agent"
                   "account"    {"name"     "root"
                                 "homePage" "http://localhost:8080"}}]
    (-> statement
        fix-statement-context-activities
        (assoc "id"        id
               "stored"    stored
               "timestamp" timestamp
               "authority" authority
               "version"   "1.0.3"))))

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
  (let [update-stmt-node
        (fn [node]
          (condp (fn [spec v]
                   (and (some (partial get node)
                              ["name" "description" "display"])
                        (s/valid? spec v)))
                 node
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
            ;; else
            node))]
    (w/prewalk update-stmt-node s-data)))

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

(def actor-id-keys
  ["objectType"
   ;; actorIFI
   "mbox" "mbox_sha1sum" "account" "openid" "member"])

(def verb-id-keys
  ["id"])

(def object-id-keys
  ["objectType"
   ;; actor IFI
   "id" "mbox" "mbox_sha1sum" "account" "openid" "member"
   ;; object keys
   "actor" "verb" "object" "context" "result" "timestamp"])

;; Need to separate out `format-statement-ids` in order to avoid spec errors
;; when instrumented.

(defn- format-statement-ids* [s]
  (cond-> s
    true (update "actor" select-keys actor-id-keys)
    true (update "verb" select-keys verb-id-keys)
    true (update "object" select-keys object-id-keys)
    ;; Activity objects don't require objectType
    (= "Activity" (get-in s ["object" "objectType"]))
    (update "object" dissoc "objectType")))

(defn format-statement-ids [s]
  (cond-> s
    true
    format-statement-ids*
    (= "SubStatement" (get-in s ["object" "objectType"]))
    (update "object" format-statement-ids*)))

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
        :let [activities (if (sequential? ca-v) ca-v [ca-v])]
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

(defn statement-activities-narrow [s]
  (cond-> []
    (= "Activity"
       (get-in s ["object" "objectType"] "Activity"))
    (conj (get s "object"))))

(defn statement-activities-broad [s]
  (distinct
   (concat
    (when-let [context-activities
               (get-in s ["context" "contextActivities"])]
      (collect-context-activities context-activities))
    (when-let [ss-context-activities
               (get-in s ["object" "context" "contextActivities"])]
      (collect-context-activities ss-context-activities))
    (lazy-seq
     (when (= "SubStatement"
              (get-in s ["object" "objectType"]))
       (let [o (get s "object")]
         (concat
          (statement-activities-narrow o)
          (statement-activities-broad o))))))))

(defn statement-related-activities [s]
  (distinct
   (concat (statement-activities-narrow s)
           (statement-activities-broad s))))

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

(defn statement-agents-narrow
  "Get ONLY those agents who are in the actor and object positions
  (or groups therein)"
  [{:strs [actor object _context _authority]}]
  (let [object-type (get object "objectType")]
    (distinct
     (concat
      (ag/actor-seq actor)
      (when (#{"Agent" "Group"} object-type)
        (ag/actor-seq object))))))

(s/fdef statement-agents-narrow
  :args (s/cat :s (s/alt :statement
                         ::xs/statement
                         :sub-statement
                         ::xs/sub-statement))
  :ret (s/coll-of ::xs/actor))

(defn statement-agents-broad
  [{:strs [_actor object context _authority]}]
  (let [object-type (get object "objectType")]
    (distinct
     (concat
      ;; Don't include authority, is not interesting
      ;; and is a +1 for every authority
      (when-let [{:strs [instructor team]} context]
        (concat
         (when team
           (ag/actor-seq team))
         (when instructor
           (ag/actor-seq instructor))))
      (lazy-seq
       (when (= "SubStatement" object-type)
         (concat
          (statement-agents-narrow
           object)
          (statement-agents-broad
           object))))))))

(s/fdef statement-agents-broad
  :args (s/cat :s (s/alt :statement
                         ::xs/statement
                         :sub-statement
                         ::xs/sub-statement))
  :ret (s/coll-of ::xs/actor))

;; TODO: deprecate
(defn statement-agents [{:strs [actor object context authority]}
                        & [broad? agents-only?]]
  (let [broad?       (or broad? false)
        agents-only? (or agents-only? false)
        object-type  (get object "objectType")]
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

(defn ^{:deprecated "1.1.0"} statements-equal? [& ss]
  (apply = (map dissoc-lrs-attrs ss)))

;; NOTE: Case sensitivity is still applied to equality checks since the xAPI
;; spec is vague on what string values are case insensitive.
(defn- dissoc-statement-properties*
  [substmt? stmt]
  (let [{{stmt-act-type "objectType" :or {stmt-act-type "Agent"}}
         "actor"
         {stmt-obj-type "objectType" :or {stmt-obj-type "Activity"}}
         "object"
         {{?cat-acts "category"
           ?grp-acts "grouping"
           ?prt-acts "parent"
           ?oth-acts "other"} "contextActivities"
          ?stmt-inst "instructor"
          ?stmt-team "team"}
         "context"}
        stmt
        dissoc-activity-def
        (fn [activity] (dissoc activity "definition"))
        dissoc-activity-defs
        (partial map dissoc-activity-def)]
    (cond-> stmt
      ;; Dissoc any properties potentially generated by the lrs
      ;; `id` is not dissoc'd since it should be equal across Statements
      (not substmt?)
      dissoc-lrs-attrs
      ;; Verb displays are technically not part of the stmt
      true
      (update "verb" dissoc "display")
      ;; Add in the "Activity" value if `objectType` is not present;
      ;; not explicitly mentioned with regards to Statement Immutability,
      ;; but would be expected as convention.
      (nil? (get-in stmt ["object" "objectType"]))
      (assoc-in ["object" "objectType"] "Activity")
      ;; Activity definitions are technically not part of the stmt
      (= "Activity" stmt-obj-type)
      (update "object"
              dissoc-activity-def)
      ?cat-acts ; Also unorder context activity arrays
      (update-in ["context" "contextActivities" "category"]
                 (comp set dissoc-activity-defs))
      ?grp-acts
      (update-in ["context" "contextActivities" "grouping"]
                 (comp set dissoc-activity-defs))
      ?prt-acts
      (update-in ["context" "contextActivities" "parent"]
                 (comp set dissoc-activity-defs))
      ?oth-acts
      (update-in ["context" "contextActivities" "other"]
                 (comp set dissoc-activity-defs))
      ;; Group member arrays must be unordered
      ;; NOTE: Ignore authority unless OAuth is enabled
      (= "Group" stmt-act-type)
      (update-in ["actor" "member"]
                 set)
      (= "Group" stmt-obj-type)
      (update-in ["object" "member"]
                 set)
      (and ?stmt-inst (contains? ?stmt-inst "member"))
      (update-in ["context" "instructor" "member"]
                 set)
      (and ?stmt-team (contains? ?stmt-inst "member"))
      (update-in ["context" "team" "member"]
                 set)
      ;; Repeat the above in any Substatements
      (and (not substmt?)
           (= "SubStatement" stmt-obj-type))
      (update "object" (partial dissoc-statement-properties* true)))))

(defn dissoc-statement-properties
  "Dissociate any Statement properties in `stmt` that are an exception to
   Statement Immutability (except case insensitivity)."
  [stmt]
  (dissoc-statement-properties* false stmt))

(s/fdef dissoc-statement-properties
  :args (s/cat :statement ::xs/statement)
  :ret (s/and
        (s/conformer (partial w/postwalk (fn [x] (if (set? x) (vec x) x))))
        ::xs/statement))

;; TODO: A bunch of other functions have args in the style of `& ss`.
;; Check whether they work for zero args.
(defn statements-immut-equal?
  "Return `true` if the Statements `ss` are equal after all Statement
   Immutability properties (except case insensitivity)."
  [& ss]
  (if (not-empty ss)
    (apply = (map dissoc-statement-properties ss))
    ;; Vacuously true
    true))

(s/fdef statements-immut-equal?
  :args (s/cat :statements (s/* ::xs/statement))
  :ret boolean?)

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
    (normalize-id (get-in s ["object" "id"]))))

(defn voiding-statement? [s]
  (some-> s (get-in ["verb" "id"]) (= "http://adlnet.gov/expapi/verbs/voided")))

;; A representation of a stored attachment
;; TODO: generalize
(s/def :attachment/content
  #?(:clj (s/with-gen
            #(satisfies? clojure.java.io/IOFactory %)
            sgen/bytes)
     :cljs (s/with-gen
             object?
             (fn []
               (sgen/return #js {:name "/foo/bar"})))))

(s/def ::attachment
  (s/with-gen
    (s/keys :req-un [:attachment/content
                     :attachment/contentType
                     :attachment/sha2
                     :attachment/length])
    (fn []
      (sgen/fmap
       (fn [[^bytes content
             content-type]]
         {:content     content
          :contentType content-type
          :length      #?(:clj (count content)
                          :cljs (rand-int 100))
          :sha2        (sha-256 content)})
       (sgen/tuple
        (s/gen :attachment/content)
        (s/gen :attachment/contentType))))))

(s/def ::attachments
  (s/coll-of ::attachment))

(s/def ::attachment-path
  (s/cat
   :root-or-sub (s/? #{"object"})
   :attachment-key #{"attachments"}
   :index nat-int?))

(defn all-attachment-objects
  "Given a collection of statements, return a lazy seq of maps containing:
    :statement - the containing statement
    :attachment - the attachment object
    :attachment-path - the path of the attachment object in the statement"
  [statements]
  (mapcat
   (fn [{:strs [attachments
                object]
         :as statement}]
     (concat (for [[idx att] (map-indexed vector attachments)]
               {:statement statement
                :attachment att
                :attachment-path ["attachments" idx]})
             (for [[idx att] (map-indexed vector
                                          (get object "attachments"))]
               {:statement statement
                :attachment att
                :attachment-path ["object" "attachments" idx]})))
   statements))

(s/fdef all-attachment-objects
  :args (s/cat :statements
               (s/coll-of ::xs/statement))
  :ret (s/coll-of
        (s/keys :req-un
                [::xs/statement
                 ::xs/attachment
                 ::attachment-path])))

(defn all-attachment-hashes
  "For each statement, get any attachment hashes. If skip-file-urls is true,
   will only return sha2s from attachments w/o fileURL."
  [statements & [skip-file-urls]]
  (distinct
   (keep
    (fn [{:strs [sha2 fileUrl] :as _attachment}]
      (if skip-file-urls
        (when-not fileUrl
          sha2)
        sha2))
    (map
     :attachment
     (all-attachment-objects statements)))))

(s/fdef all-attachment-hashes
  :args (s/cat :statements
               (s/coll-of ::xs/statement)
               :skip-file-urls
               (s/? boolean?))
  :ret (s/coll-of :attachment/sha2))

(defn statement-rel-docs
  "Get related activities and agents"
  [statement]
  {:agents     (distinct (statement-agents statement true true))
   :activities (into []
                     (vals
                      (reduce
                       (fn [m {:strs [id] :as a}]
                         (update m id ac/merge-activity a))
                       {}
                       (distinct
                        (statement-related-activities statement)))))})

(s/def :statement-rel-docs/agents
  (s/coll-of ::xs/agent))

(s/def :statement-rel-docs/activities
  (s/coll-of ::xs/activity))

(s/fdef statement-rel-docs
  :args (s/cat :statement
               ::xs/statement)
  :ret (s/keys :req-un [:statement-rel-docs/agents
                        :statement-rel-docs/activities]))
