(ns mem-lrs.impl.xapi
  "A naive LRS implementation in memory"
  (:require [com.yetanalytics.lrs.protocol.xapi.about :as about]
            [com.yetanalytics.lrs.protocol.xapi.statements :as statements]
            [com.yetanalytics.lrs.protocol.xapi.agents :as agents]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [clojure.data.priority-map :as pm]
            [clojure.data.json :as json]
            [clojure.walk :as w]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [ring.util.codec :as codec])
  (:import [java.time Instant]
           [clojure.data.priority_map PersistentPriorityMap]))

(set! *warn-on-reflection* true)

;; State

(defn statements-priority-map [& key-vals]
  (apply
   pm/priority-map-keyfn-by
   #(get % "stored")
   #(compare %2 %1)
   key-vals))

(defn s-pm-gen-fn []
  (sgen/return (pm/priority-map-keyfn-by
                #(get % "stored")
                #(compare %2 %1))))

(s/def ::statements-priority-map
  (s/with-gen (s/and #(instance? PersistentPriorityMap %)
                     ;; It's a map of statement id to statement
                     (s/map-of :statement/id
                                ::xs/lrs-statement))
    s-pm-gen-fn))

(s/fdef statements-priority-map
        :args (s/* (s/cat :id :statement/id
                          :statement ::xs/lrs-statement))
        :ret ::statements-priority-map)

(s/def :state/statements
  ::statements-priority-map)

(s/def :state/voided-statements
  (s/map-of :statement/id
            ::xs/lrs-statement))

(s/def :state/activities
  (s/map-of ::xs/iri
            ::xs/activity))

(def ifi-keys #{"mbox" "mbox_sha1sum" "openid" "account"})

(s/def ::ifi-lookup
  (s/or
   :mbox-lookup (s/tuple #{"mbox"} :agent/mbox)
   :mbox_sha1sum-lookup (s/tuple #{"mbox_sha1sum"} :agent/mbox_sha1sum)
   :openid-lookup (s/tuple #{"openid"} :agent/openid)
   :account-lookup (s/tuple #{"account"} :agent/account)))

(s/def :state/agents
  (s/map-of ::ifi-lookup
            :xapi.agents.GET.response/person))

(s/def ::state
  (s/keys :req [:state/statements
                :state/voided-statements]))

(defn empty-state []
  {:state/statements
   (statements-priority-map)
   :state/voided-statements {}
   :state/activities {}
   :state/agents {}})

(s/fdef empty-state
        :args (s/cat)
        :ret ::state)

;; Activity
(defn merge-activity
  [{?id-1 "id"
    ?def-1 "definition"
    :as ?a-1}
   {id-2 "id"
    ?def-2 "definition"
    :as a-2}]
  (if ?a-1
    (cond-> {"id" ?id-1
             "objectType" "Activity"}
      (or ?def-1 ?def-2)
      (assoc "definition"
             (merge-with
              merge
              ?def-1
              (select-keys ?def-2
                           ["name"
                            "description"]))))
    a-2))

(s/fdef merge-activity
        :args (s/cat :a-1 (s/alt :nil nil?
                                 :activity ::xs/activity)
                     :a-2 ::xs/activity)
        :ret ::xs/activity)

(defn store-activity [activities-map activity]
  (update activities-map (get activity "id") merge-activity activity))

(s/fdef store-activity
        :args (s/cat :activities :state/activities
                     :activity ::xs/activity)
        :ret :state/activities)

(defn store-activities [activities-map activities]
  (reduce store-activity
          activities-map
          activities))

(s/fdef store-activities
        :args (s/cat :activities-map :state/activities
                     :activities (s/coll-of ::xs/activity))
        :ret :state/activities)

;; Person/agents

(def person-vector-keys (vec (conj ifi-keys "name")))

(defn person-conj [person agent]
  (reduce-kv
   (fn [p k v]
     (update p k (comp
                  (partial into [] (distinct))
                  (fnil conj []))
             v))
   (or person {"objectType" "Person"})
   (select-keys agent person-vector-keys)))

(s/fdef person-conj
        :args (s/cat :person
                     (s/alt :person :xapi.agents.GET.response/person
                            :nil nil?)
                     :agent
                     ::xs/agent)
        :ret :xapi.agents.GET.response/person)

(defn person [& agents]
  (reduce
   person-conj
   {"objectType" "Person"}
   agents))

(s/fdef person
        :args (s/cat :agent (s/* ::xs/agent))
        :ret :xapi.agents.GET.response/person)

(defn find-ifi [actor]
  (some (partial find actor)
        ifi-keys))

(s/fdef find-ifi
        :args (s/cat :actor ::xs/actor)
        :ret ::ifi-lookup)

(defn store-agent [agents-map agent]
  (let [ifi-lookup (find-ifi agent)]
    (update agents-map ifi-lookup person-conj agent)))

(s/fdef store-agent
        :args (s/cat :agents-map :state/agents
                     :agent ::xs/agent)
        :ret :state/agents)

(defn store-agents [agents-map agents]
  (reduce store-agent
          agents-map
          agents))

(s/fdef store-agents
        :args (s/cat :agents-map :state/agents
                     :agent (s/coll-of ::xs/agent))
        :ret :state/agents)

;; Statement fns
(defn now-stamp []
  (str (Instant/now)))

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
  (let [id (or id (str (java.util.UUID/randomUUID)))
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
                 (-> node
                     (update "name" canonize-lmap ltags)
                     (update "description" canonize-lmap ltags))
                 ::xs/verb
                 (update node "display" canonize-lmap ltags)
                 ::xs/attachment
                 (update node "display" canonize-lmap ltags)
                 node))
             s-data))

(s/fdef format-canonical
        :args (s/cat :statement-data
                     (s/alt :single-statement ::xs/lrs-statement
                            :multiple-statements ::xs/lrs-statements)
                     :ltags
                     (s/coll-of ::xs/language-tag))
        :ret (s/or :single-statement ::xs/lrs-statement
                   :multiple-statements ::xs/lrs-statements))

(defn format-statement-ids [s]
  (-> s
      (update "actor" select-keys ["objectType" "mbox" "mbox_sha1sum" "account" "openid" "member"])
      (update "verb" select-keys ["id"])
      (update "object" select-keys ["objectType" "id"])))

(s/fdef format-statement-ids
        :args (s/cat :s ::xs/lrs-statement)
        :ret ::xs/lrs-statement)

(defn format-ids [ss]
  (map format-statement-ids ss))

(s/fdef format-ids
        :args (s/cat :ss ::xs/lrs-statements)
        :ret ::xs/lrs-statements)

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

(defn actor-seq
  "Given an agent/group, return a seq of all agents/groups expressed"
  [actor]
  (cons actor
        (when (= "Group" (get actor "objectType"))
          (get actor "member"))))

(s/fdef actor-seq
        :args (s/cat :actor ::xs/actor)
        :ret (s/coll-of ::xs/actor))

(defn statement-agents [{:strs [actor object context authority] :as s} & [broad? agents-only?]]
  (let [broad? (or broad? false)
        agents-only? (or agents-only? false)
        object-type (get object "objectType")]
    (cond->> (distinct
              (concat
               (actor-seq actor)
               (when (#{"Agent" "Group"}
                      object-type)
                 (actor-seq object))
               (when broad?
                 (concat
                  (when authority
                    (actor-seq authority))
                  (when-let [{:strs [instructor team]} context]
                    (concat
                     (when team
                       (actor-seq team))
                     (when instructor
                       (actor-seq instructor))))
                  (lazy-seq
                   (when (= "SubStatement" object-type)
                     (statement-agents
                      object true agents-only?)))))))
      agents-only? (remove #(= "Group" (get % "objectType"))))))

(s/fdef statement-agents
        :args (s/cat :s ::xs/statement
                     :broad? (s/? boolean?)
                     :agents-only? (s/? boolean?))
        :ret (s/coll-of ::xs/actor))

(defn ifi-match? [a1 a2]
  (or (some (fn [[a1m a2m]]
              (= a1m a2m))
            (for [ifi-key ["mbox" "mbox_sha1sum" "openid" "account"]
                  :let [a1-match (get a1 ifi-key)]
                  :when a1-match
                  :let [a2-match (get a2 ifi-key)]
                  :when a2-match]
              [a1-match a2-match]))
      false))

(s/fdef ifi-match?
        :args (s/cat :a1 ::xs/actor
                     :a2 ::xs/actor)
        :ret boolean?)

;; Transactions into the state
(defn statements-equal? [& ss]
  (apply = (map dissoc-lrs-attrs ss)))

(defn voiding-statement? [s]
  (some-> s (get-in ["verb" "id"]) (= "http://adlnet.gov/expapi/verbs/voided")))

(defn transact-statements [lrs-state
                           new-statements]
  (reduce
   (fn [state statement]
     (-> (let [s-id (get statement "id")]
           (if-let [extant (get-in state [:state/statements s-id])]
             (if (statements-equal? extant statement)
               state ;; No change to LRS
               (statements/throw-statement-conflict statement extant))
             (if (voiding-statement? statement)
               (if-let [void-target-id (and (= "StatementRef" (get-in statement
                                                                      ["object"
                                                                       "objectType"]))
                                            (get-in statement ["object" "id"]))]
                 (if-let [extant-target (get-in state [:state/statements void-target-id])]
                   (if (voiding-statement? extant-target)
                     ;; can't void a voiding statement
                     (statements/throw-invalid-voiding-statement statement)
                     (-> state
                         (assoc-in [:state/statements s-id] statement)
                         (update :state/statements dissoc void-target-id)
                         (assoc-in [:state/voided-statements void-target-id] extant-target)))
                   (assoc-in state [:state/statements s-id] statement))
                 (statements/throw-invalid-voiding-statement statement))
               (assoc-in state [:state/statements s-id] statement))))
         (update :state/activities
                 store-activities
                 (statement-related-activities statement))
         (update :state/agents
                 store-agents
                 (statement-agents statement true true))))
   lrs-state
   new-statements))

(s/fdef transact-statements
        :args (s/cat :lrs-state ::state
                     :statements ::xs/lrs-statements)
        :ret ::state)

(defn new-lrs [{:keys [xapi-path-prefix
                       statements-result-max]
                :or {xapi-path-prefix ""
                     statements-result-max 50}}]
  (let [state (atom (empty-state)
                    :validator (fn [s]
                                 (or (s/valid? ::state s)
                                     (s/explain ::state s)
                                     (clojure.pprint/pprint s))))]

    (reify
      about/AboutResource
      (get-about [_]
        {:version ["1.0.0",
                   "1.0.1",
                   "1.0.2",
                   "1.0.3"]})
      statements/StatementsResource
      (store-statements [_ statements attachments]
        (let [prepared-statements (map prepare-statement
                                       statements)]
          (swap! state transact-statements prepared-statements)
          (into []
                (map #(get % "id")
                     prepared-statements))))
      (get-statements [_ {:keys [statementId
                                 voidedStatementId
                                 verb
                                 activity
                                 registration
                                 related_activities
                                 related_agents
                                 since
                                 until
                                 limit
                                 attachments
                                 ascending
                                 page]
                          format-type :format
                          agent-json :agent
                          :as params
                          :or {related_activities false
                               related_agents false
                               limit 0
                               attachments false
                               ascending false
                               page 0
                               format-type "exact"}} ltags]
        (if (or statementId voidedStatementId)
          (when-let [result (cond
                              statementId
                              (get-in @state [:state/statements statementId])
                              voidedStatementId
                              (get-in @state [:state/voided-statements voidedStatementId]))]
            (cond-> result
              (= "canonical" format-type)
              (format-canonical ltags)
              (= "ids" format-type)
              format-statement-ids))
          ;; otherwise, this is a paged sequential query
          (let [page (when page
                       (if (string? page)
                         (Long/parseLong ^String page)
                         page))
                page-size (cond
                            (= limit 0)
                            statements-result-max
                            (< 0 limit statements-result-max)
                            limit
                            :else statements-result-max)
                results-base (cond->> (vals (:state/statements @state))
                               until (drop-while #(= -1 (compare until (get % "stored"))))
                               since (take-while #(> 0 (compare since (get % "stored"))))
                               ascending reverse
                               ;; simple filters
                               verb (filter #(= verb (get-in % ["verb" "id"])))
                               registration (filter #(= registration
                                                        (get-in % ["context" "registration"])))
                               ;; complex filters
                               activity (filter
                                         (if related_activities
                                           ;; complex activity-filter
                                           (fn [s]
                                             (some (partial = activity)
                                                   (statement-related-activity-ids s)))
                                           ;; simple activity filter
                                           #(= activity (get-in % ["object" "id"]))))
                               agent-json (filter
                                           (let [agent-query (json/read-str agent-json)
                                                 agents-fn (if related_agents
                                                             #(statement-agents % true)
                                                             #(statement-agents % false))]
                                             (fn [s]
                                               (some (partial ifi-match? agent-query)
                                                     (agents-fn s)))))
                               #_(and limit
                                    (not= limit 0)) #_(take limit))
                paged (partition-all page-size results-base)
                this-page (try (nth paged page)
                               (catch java.lang.IndexOutOfBoundsException e
                                 (list)))
                more? (seq (drop (inc page) paged))]
            (cond-> {"statements" (into []
                                        (cond-> this-page
                                          (= "canonical" format-type)
                                          (format-canonical ltags)
                                          (= "ids" format-type)
                                          format-ids))}
              more? (assoc "more"
                           (str xapi-path-prefix
                                "/xapi/statements?"
                                (codec/form-encode
                                 (update params "page" (fnil inc 0)))))))))
      agents/AgentInfoResource
      (get-person [_ params]
        (or (get-in @state [:state/agents
                            (find-ifi (json/read-str (:agent params)))]
                    (person)))))))

(s/def ::xapi-path-prefix
  string?)

(s/def ::statements-result-max
  pos-int?)

(s/fdef new-lrs
        :args (s/cat :options
                     (s/keys :opt-un [::xapi-path-prefix
                                      ::statements-result-max]))
        :ret ::statements/statements-resource-instance)

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument '[mem-lrs.impl.xapi/now-stamp
                      mem-lrs.impl.xapi/empty-state
                      mem-lrs.impl.xapi/statement-agents
                      mem-lrs.impl.xapi/fix-statement-context-activities
                      mem-lrs.impl.xapi/fix-context-activities
                      mem-lrs.impl.xapi/dissoc-lrs-attrs
                      mem-lrs.impl.xapi/statements-priority-map
                      mem-lrs.impl.xapi/actor-seq
                      mem-lrs.impl.xapi/format-statement-ids
                      mem-lrs.impl.xapi/collect-context-activities
                      mem-lrs.impl.xapi/new-lrs
                      mem-lrs.impl.xapi/prepare-statement
                      mem-lrs.impl.xapi/canonize-lmap
                      mem-lrs.impl.xapi/ifi-match?
                      mem-lrs.impl.xapi/find-ifi
                      mem-lrs.impl.xapi/transact-statements
                      mem-lrs.impl.xapi/statement-related-activities
                      mem-lrs.impl.xapi/statement-related-activity-ids
                      mem-lrs.impl.xapi/merge-activity
                      mem-lrs.impl.xapi/store-activity
                      mem-lrs.impl.xapi/store-activities
                      mem-lrs.impl.xapi/format-ids
                      mem-lrs.impl.xapi/format-canonical
                      mem-lrs.impl.xapi/person-conj
                      mem-lrs.impl.xapi/person
                      mem-lrs.impl.xapi/store-agent
                      mem-lrs.impl.xapi/store-agents])

  (def long-s (with-open [rdr (io/reader "dev-resources/statements/long.json")]
                (json/read rdr)))

  (s/exercise-fn 'mem-lrs.impl.xapi/person-conj)
  (s/exercise-fn 'mem-lrs.impl.xapi/merge-activity)

  (s/exercise-fn 'mem-lrs.impl.xapi/transact-statements)


  )
