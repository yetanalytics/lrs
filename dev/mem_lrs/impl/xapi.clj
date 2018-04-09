(ns mem-lrs.impl.xapi
  "A naive LRS implementation in memory"
  (:require [com.yetanalytics.lrs.protocol.xapi.about :as about]
            [com.yetanalytics.lrs.protocol.xapi.statements :as statements]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [clojure.data.priority-map :as pm]
            [clojure.data.json :as json]
            [clojure.walk :as w]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [ring.util.codec :as codec])
  (:import [java.time Instant]))

(set! *warn-on-reflection* true)

(s/def :state/statements
  (s/map-of :statement/id
            ::xs/statement))

(s/def :state/voided-statements
  (s/map-of :statement/id
            ::xs/statement))

(s/def ::state
  (s/keys :req [:state/statements
                :state/voided-statements]))

(defn now-stamp []
  (str (Instant/now)))

(defn fix-context-activities [ca-map]
  (reduce-kv
   (fn [m k v]
     (assoc m k (if (sequential? v)
                  v
                  [v])))
   {}
   ca-map))

(defn fix-statement-context-activities [{:strs [context object] :as s}]
  (cond-> s
    (get context "contextActivities")
    (update-in ["context" "contextActivities"] fix-context-activities)

    (get-in object ["context" "contextActivities"])
    (update-in ["object" "context" "contextActivities"] fix-context-activities)))

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

(defn dissoc-lrs-attrs [s]
  (dissoc s
          "stored"
          "timestamp"
          "authority"
          "version"))

(defn statements-equal? [& ss]
  (apply = (map dissoc-lrs-attrs ss)))

(defn voiding-statement? [s]
  (some-> s (get-in ["verb" "id"]) (= "http://adlnet.gov/expapi/verbs/voided")))

(defn transact-statements [lrs-state
                           new-statements]
  (reduce
   (fn [state statement]
     (let [s-id (get statement "id")]
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
           (assoc-in state [:state/statements s-id] statement)))))
   lrs-state
   new-statements))

(defn statements-priority-map [& key-vals]
  (apply
   pm/priority-map-keyfn-by
   #(get % "stored")
   #(compare %2 %1)
   key-vals))

(defn canonize-lmap [lmap ltags]
  (conj {}
        (or (some (fn [ltag]
              (find lmap ltag))
            ltags)
            (first lmap))))

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

(defn format-statement-ids [s]
  (-> s
      (update "actor" select-keys ["objectType" "mbox" "mbox_sha1sum" "account" "openid" "member"])
      (update "verb" select-keys ["id"])
      (update "object" select-keys ["objectType" "id"])))

(defn format-ids [ss]
  (map format-statement-ids ss))


(defn collect-ca-ids [ca-map]
  (for [[_ ca-v] ca-map
        :let [activities (if (sequential? ca-v)
                           ca-v
                           [ca-v])]
        activity activities]
    (get activity "id")))

(defn statement-related-activity-ids [s]
  (distinct (cond->> (concat
                     (when-let [context-activities (get-in s ["context" "contextActivities"])]
                       (collect-ca-ids context-activities))
                     (when-let [ss-context-activities (get-in s ["object" "context" "contextActivities"])]
                       (collect-ca-ids ss-context-activities)))
              (= "Activity" (get-in s ["object" "objectType"] "Activity"))
              (cons (get-in s ["object" "id"])))))

(defn actor-seq
  "Given an agent/group, return a seq of all agents/groups expressed"
  [actor]
  (cons actor
        (when (= "Group" (get actor "objectType"))
          (get actor "member"))))

(defn statement-agents [{:strs [actor object context authority] :as s} & [broad?]]
  (let [broad? (or broad? false)
        object-type (get object "objectType")]
    (distinct
     (concat
      (actor-seq actor)
      (when (#{"Agent" "Group"} object-type)
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
             object true)))))))))

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

(defn new-lrs [{:keys [xapi-path-prefix
                       statements-result-max]
                :or {xapi-path-prefix ""
                     statements-result-max 50}}]
  (let [state (atom {:state/statements
                     (statements-priority-map)
                     :state/voided-statements {}}
                    :validator (partial s/valid? ::state))]

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
          (let [page-size (cond
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
                                 (update params "page" (fnil inc 0))))))))))))

(comment

  (comment

    (def long-s (with-open [rdr (io/reader "dev-resources/statements/long.json")]
                  (json/read rdr)))

    (def lrs (new-lrs {}))

    (statements/store-statements lrs (sgen/sample (s/gen ::xs/statement) 100) [])


    (-> (time (statements/get-statements lrs {:format "ids"
                                        } []))
        (get "statements")
        count)




    (require '[clojure.spec.gen.alpha :as sgen])
    (clojure.repl/doc sgen/sample)
    (sgen/sample (s/gen ::xs/statement) 100)
    (time (statements/get-statements lrs {:format "ids"
                                    :agent (json/write-str
                                            {"name" "Ena Hills",
                                             "mbox_sha1sum" "ebd31e95054c018b10727ccffd2ef2ec3a016ee9",
                                             "objectType" "Agent"})} []))
    (clojure.pprint/pprint long-s)
    (time (statement-related-activity-ids long-s))
    (time (count (statement-agents long-s false)))
    (vals (statements-priority-map (get long-s "id") long-s))
    (time (canonize-lmaps long-s ["en-US"]))
    )





  )
