(ns mem-lrs.impl.xapi
  "A naive LRS implementation in memory"
  (:require [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.agents :as ag]
            [com.yetanalytics.lrs.xapi.activities :as ac]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment
             :as att]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [clojure.data.priority-map :as pm]
            [clojure.data.json :as json]
            [clojure.walk :as w]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [ring.util.codec :as codec]))

(set! *warn-on-reflection* true)

;; State
(s/def :state/statements
  ::ss/statements-priority-map)

(s/def :state/voided-statements
  (s/map-of :statement/id
            ::xs/lrs-statement))

(s/def :state/attachments
  ;; track attachments
  (s/map-of :attachment/sha2
            ::att/attachment))

(defn store-attachments
  [atts-map attachments]
  (reduce
   (fn [m {:keys [xapi-hash] :as att}]
     (assoc m xapi-hash att))
   atts-map
   attachments))

(s/fdef store-attachments
        :args (s/cat :atts-map :state/attachments
                     :attachments ::att/attachments)
        :ret :state/attachments)

(s/def :state/activities
  (s/map-of ::xs/iri
            ::xs/activity))

(defn store-activity [activities-map activity]
  (update activities-map (get activity "id") ac/merge-activity activity))

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


(s/def :state/agents
  (s/map-of ::ag/ifi-lookup
            :xapi.agents.GET.response/person))


(defn store-agent [agents-map agent]
  (let [ifi-lookup (ag/find-ifi agent)]
    (update agents-map ifi-lookup ag/person-conj agent)))

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

(s/def ::state
  (s/keys :req [:state/statements
                :state/voided-statements
                :state/activities
                :state/agents
                :state/attachments]))

(defn empty-state []
  {:state/statements
   (ss/statements-priority-map)
   :state/voided-statements {}
   :state/activities {}
   :state/agents {}
   :state/attachments {}})

(s/fdef empty-state
        :args (s/cat)
        :ret ::state)


(defn transact-statements [lrs-state
                           new-statements
                           attachments]
  (reduce
   (fn [state statement]
     (-> (let [s-id (get statement "id")]
           (if-let [extant (get-in state [:state/statements s-id])]
             (if (ss/statements-equal? extant statement)
               state ;; No change to LRS
               (p/throw-statement-conflict statement extant))
             (if (ss/voiding-statement? statement)
               (if-let [void-target-id (and (= "StatementRef" (get-in statement
                                                                      ["object"
                                                                       "objectType"]))
                                            (get-in statement ["object" "id"]))]
                 (if-let [extant-target (get-in state [:state/statements void-target-id])]
                   (if (ss/voiding-statement? extant-target)
                     ;; can't void a voiding statement
                     (p/throw-invalid-voiding-statement statement)
                     (-> state
                         (assoc-in [:state/statements s-id] statement)
                         (update :state/statements dissoc void-target-id)
                         (assoc-in [:state/voided-statements void-target-id] extant-target)))
                   (assoc-in state [:state/statements s-id] statement))
                 (p/throw-invalid-voiding-statement statement))
               (assoc-in state [:state/statements s-id] statement))))
         (update :state/activities
                 store-activities
                 (ss/statement-related-activities statement))
         (update :state/agents
                 store-agents
                 (ss/statement-agents statement true true))
         (update :state/attachments
                 store-attachments
                 attachments)
         ))
   lrs-state
   new-statements))

(s/fdef transact-statements
        :args (s/cat :lrs-state ::state
                     :statements ::xs/lrs-statements
                     :attachments ::att/attachments)
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
      p/AboutResource
      (get-about [_]
        {:version ["1.0.0",
                   "1.0.1",
                   "1.0.2",
                   "1.0.3"]})
      p/StatementsResource
      (store-statements [_ statements attachments]
        (let [prepared-statements (map ss/prepare-statement
                                       statements)]
          (swap! state transact-statements prepared-statements attachments)
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
              (ss/format-canonical ltags)
              (= "ids" format-type)
              ss/format-statement-ids))
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
                                                   (ss/statement-related-activity-ids s)))
                                           ;; simple activity filter
                                           #(= activity (get-in % ["object" "id"]))))
                               agent-json (filter
                                           (let [agent-query (json/read-str agent-json)
                                                 agents-fn (if related_agents
                                                             #(ss/statement-agents % true)
                                                             #(ss/statement-agents % false))]
                                             (fn [s]
                                               (some (partial ag/ifi-match? agent-query)
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
                                          (ss/format-canonical ltags)
                                          (= "ids" format-type)
                                          ss/format-ids))}
              more? (assoc "more"
                           (str xapi-path-prefix
                                "/xapi/statements?"
                                (codec/form-encode
                                 (update params "page" (fnil inc 0)))))))))
      p/AgentInfoResource
      (get-person [_ params]
        (let [ifi-lookup (ag/find-ifi (:agent params))]
          ;; TODO: extract this fn
          (get-in @state
                  [:state/agents
                   ifi-lookup]
                  (ag/person (:agent params)))))
      p/ActivityInfoResource
      (get-activity [_ params]
        (get-in @state
                [:state/activities
                 (:activityId params)])))))

(s/def ::xapi-path-prefix
  string?)

(s/def ::statements-result-max
  pos-int?)

(s/fdef new-lrs
        :args (s/cat :options
                     (s/keys :opt-un [::xapi-path-prefix
                                      ::statements-result-max]))
        :ret ::p/statements-resource-instance)

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument [`empty-state
                     `new-lrs
                     `transact-statements
                     `store-activity
                     `store-activities
                     `store-agent
                     `store-agents
                     `ss/now-stamp
                     `ss/statement-agents
                     `ss/fix-statement-context-activities
                     `ss/fix-context-activities
                     `ss/dissoc-lrs-attrs
                     `ss/statements-priority-map
                     `ss/format-statement-ids
                     `ss/format-ids
                     `ss/format-canonical
                     `ss/collect-context-activities
                     `ss/prepare-statement
                     `ss/canonize-lmap
                     `ss/statement-related-activities
                     `ss/statement-related-activity-ids
                     `ac/merge-activity
                     `ag/actor-seq
                     `ag/person-conj
                     `ag/person
                     `ag/ifi-match?
                     `ag/find-ifi])

  (def long-s (with-open [rdr (io/reader "dev-resources/statements/long.json")]
                (json/read rdr)))


  )
