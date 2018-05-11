(ns com.yetanalytics.lrs.impl.memory
  "A naive LRS implementation in memory"
  (:require [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.agents :as ag]
            [com.yetanalytics.lrs.xapi.activities :as ac]
            [com.yetanalytics.lrs.xapi.document :as doc]
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
            ::ss/attachment))

(s/def :state/refs
  ;; Map of referring statement ids to target ids
  (s/map-of :statement/id
            :statement/id))

(defn store-ref
  "Store a statement reference relation"
  [refs-map ref-id target-id]
  (assoc refs-map ref-id target-id))

(s/fdef store-ref
        :args (s/cat
               :ref-id :statement/id
               :target-id :statement/id))

(defn store-attachments
  [atts-map attachments]
  (reduce
   (fn [m {:keys [sha2] :as att}]
     (assoc m sha2 att))
   atts-map
   attachments))

(s/fdef store-attachments
        :args (s/cat :atts-map :state/attachments
                     :attachments ::ss/attachments)
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

(def state-tuple-re-spec
  (s/cat :activity-id
         :activity/id
         :agent
         ::xs/agent
         :registration
         (s/? :context/registration)))

(s/def ::state-tuple
  (s/with-gen state-tuple-re-spec
    #(sgen/fmap
      (partial into [])
      (s/gen state-tuple-re-spec))))


(s/def :state/documents
  (s/map-of (s/or :state-tuple
                  ::state-tuple
                  :activity
                  :activity/id
                  :agent
                  ::xs/agent)
            ::doc/documents-priority-map))

(defn- document-keys [{:keys [activityId
                              agent
                              stateId
                              profileId
                              registration]} document]
  (cond
    stateId
    {:context-key (cond-> [activityId agent]
                    registration (conj registration))
     :document-key stateId
     :document (cond-> (assoc document
                              :id stateId)
                 registration
                 (assoc :registration registration))}
    (and profileId
         agent)
    {:context-key agent
     :document-key profileId
     :document (assoc document
                      :id profileId)}
    (and profileId
         activityId)
    {:context-key activityId
     :document-key profileId
     :document (assoc document
                      :id profileId)}))

(defn- param-keys [{:keys [activityId
                           agent
                           stateId
                           profileId
                           registration]}]
  (cond
    stateId
    {:context-key (cond-> [activityId agent]
                    registration (conj registration))
     :document-key stateId}
    (and profileId
         agent)
    {:context-key agent
     :document-key profileId}
    (and profileId
         activityId)
    {:context-key activityId
     :document-key profileId}))

(defn- param-keys-query [{:keys [activityId
                                 agent
                                 stateId
                                 profileId
                                 registration
                                 since] :as params}]
  (cond
    (and activityId agent)
    {:context-key (cond-> [activityId agent]
                    registration (conj registration))
     :query (select-keys params [:since])}
    agent
    {:context-key agent
     :query (select-keys params [:since])}
    activityId
    {:context-key activityId
     :query (select-keys params [:since])}))

(defn transact-document
  [documents params document merge?]
  (let [{:keys [context-key
                document-key
                document]} (document-keys
                            params document)]
    (update (if merge?
              (update-in documents [context-key document-key] doc/merge-or-replace document)
              (assoc-in documents [context-key document-key] (assoc
                                                              document
                                                              :updated
                                                              (doc/updated-stamp))))
            context-key
            #(conj (doc/documents-priority-map) %))))

(s/fdef transact-document
        :args (s/cat :documents :state/documents
                     :params ::p/set-document-params
                     :document :com.yetanalytics.lrs.xapi/document
                     :merge (s/nilable boolean?))
        :ret :state/documents)

(defn get-document
  [state params]
  (let [{:keys [context-key
                document-key]} (param-keys params)]
    (get-in state [:state/documents
                   context-key
                   document-key])))

(s/fdef get-document
        :args (s/cat :documents :state/documents
                     :params ::p/get-document-params)
        :ret :state/documents)

(defn get-document-ids
  [state params]
  (let [{context-key :context-key
         {?since :since} :query} (param-keys-query params)]
    (mapv :id
          (cond->> (some-> (get-in state [:state/documents context-key])
                           vals)
           ?since (drop-while (fn [{:keys [updated]}]
                                (< -1 (compare ?since updated))))))))

(s/fdef get-document-ids
        :args (s/cat :state ::state
                     :params ::p/get-document-ids-params)
        :ret (s/coll-of ::doc/id))

(defn delete-document
  [documents params]
  (let [{:keys [context-key
                document-key]}
        (param-keys
         params)]
    (update documents context-key (fnil dissoc {}) document-key)))

(s/fdef delete-document
        :args (s/cat :documents :state/documents
                     :params ::p/delete-document-params)
        :ret :state/documents)

(defn delete-documents
  [documents params]
  (let [{:keys [context-key]}
        (param-keys-query
         params)]
    (dissoc documents context-key)))

(s/fdef delete-documents
        :args (s/cat :documents :state/documents
                     :params :xapi.document.state/context-params)
        :ret :state/documents)

(s/def ::state
  (s/keys :req [:state/statements
                :state/voided-statements
                :state/refs
                :state/activities
                :state/agents
                :state/attachments
                :state/documents]))

(defn empty-state
  []
  {:state/statements
   (ss/statements-priority-map)
   :state/voided-statements {}
   :state/refs {}
   :state/activities {}
   :state/agents {}
   :state/attachments {}
   :state/documents {}})

(s/fdef empty-state
        :args (s/cat)
        :ret ::state)


(defn transact-statements [lrs-state
                           new-statements
                           attachments]
  (reduce
   (fn [state statement]
     (-> (let [s-id (get statement "id")
               ?ref-target-id (ss/statement-ref-id statement)]
           (cond-> (if-let [extant (get-in state [:state/statements s-id])]
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
                       (assoc-in state [:state/statements s-id] statement)))
             ?ref-target-id (update :state/refs store-ref s-id ?ref-target-id)))
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
                     :attachments ::ss/attachments)
        :ret ::state)


(defprotocol DumpableMemoryLRS
  (dump [_] "Return the LRS's state in EDN"))

(defn new-lrs [{:keys [xapi-path-prefix
                       statements-result-max
                       init-state]
                :or {xapi-path-prefix ""
                     statements-result-max 50
                     init-state (empty-state)}}]
  (let [state (atom init-state
                    :validator (fn [s]
                                 (or (s/valid? ::state s)
                                     (s/explain ::state s)
                                     #_(clojure.pprint/pprint (:state/documents s))
                                     #_(clojure.pprint/pprint s))))]

    (reify
      p/AboutResource
      (-get-about [_]
        {:version ["1.0.0",
                   "1.0.1",
                   "1.0.2",
                   "1.0.3"]})
      p/StatementsResource
      (-store-statements [_ statements attachments]
        (let [prepared-statements (map ss/prepare-statement
                                       statements)]
          (swap! state transact-statements prepared-statements attachments)
          (into []
                (map #(get % "id")
                     prepared-statements))))
      (-get-statements [_ {:keys [statementId
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
                                  page
                                  agent]
                           format-type :format
                           :as params
                           :or {related_activities false
                                related_agents false
                                limit 0
                                attachments false
                                ascending false
                                page 0
                                format-type "exact"}} ltags]
        (let [state' @state]
          (if (or statementId voidedStatementId)
            (when-let [result (cond
                                statementId
                                (get-in state' [:state/statements statementId])
                                voidedStatementId
                                (get-in state' [:state/voided-statements voidedStatementId]))]
              {:statement
               (cond-> result
                 (= "canonical" format-type)
                 (ss/format-canonical ltags)
                 (= "ids" format-type)
                 ss/format-statement-ids)
               :attachments (into []
                                  (when result
                                    (keep (:state/attachments state')
                                          (ss/all-attachment-hashes [result]))))})
            ;; otherwise, this is a paged sequential query
            (let [page (or
                        (if (string? page)
                          (Long/parseLong ^String page)
                          page)
                        0)
                  page-size (cond
                              (= limit 0)
                              statements-result-max
                              (< 0 limit statements-result-max)
                              limit
                              :else statements-result-max)
                  results-base
                  (cond->> (vals (:state/statements state'))
                    since (drop-while #(< -1 (compare since (get % "stored"))))
                    until (take-while #(< -1 (compare until (get % "stored"))))
                    ascending reverse
                    ;; simple filters
                    verb (filter #(or
                                   ;; direct
                                   (= verb (get-in % ["verb" "id"]))
                                   ;; via reference
                                   (and (ss/statement-ref? %)
                                        (let [ref-id (get % "id")
                                              target-id (ss/statement-ref-id %)]
                                          ;; non-void statement
                                          (= verb (get-in state' [:state/statements
                                                                  target-id
                                                                  "verb"
                                                                  "id"]))
                                          (= verb (get-in state' [:state/voided-statements
                                                                  target-id
                                                                  "verb"
                                                                  "id"]))))))
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
                    agent (filter
                           (let [agents-fn (if related_agents
                                             #(ss/statement-agents % true)
                                             #(ss/statement-agents % false))]
                             (fn [s]
                               (some (partial ag/ifi-match? agent)
                                     (agents-fn s)))))
                    #_(and limit
                           (not= limit 0)) #_(take limit))
                  paged (partition-all page-size results-base)
                  this-page (try (nth paged page)
                                 (catch java.lang.IndexOutOfBoundsException e
                                   (list)))
                  more? (seq (drop (inc page) paged))
                  statement-result (cond-> {"statements"
                                            (into []
                                                  (cond-> this-page
                                                    (= "canonical" format-type)
                                                    (ss/format-canonical ltags)
                                                    (= "ids" format-type)
                                                    ss/format-ids))}
                                     more? (assoc "more"
                                                  (str xapi-path-prefix
                                                       "/xapi/statements?"
                                                       (codec/form-encode
                                                        (cond-> (assoc params :page (inc page))
                                                          ;; Re-encode the agent if present
                                                          agent (assoc :agent (json/write-str agent)))))))]
              {:statement-result statement-result
               :attachments (into []
                                  (when attachments
                                    (keep
                                     (:state/attachments state')
                                     (ss/all-attachment-hashes this-page))))}))))
      p/DocumentResource
      (-set-document [_ params document merge?]
        (swap! state update :state/documents transact-document params document merge?)
        )
      (-get-document [_ params]
        (get-document @state params))
      (-get-document-ids [_ params]
        (get-document-ids @state params))
      (-delete-document [_ params]
        (swap! state update :state/documents delete-document params))
      (-delete-documents [_ params]
        (swap! state update :state/documents delete-documents params))
      p/AgentInfoResource
      (-get-person [_ params]
        (let [ifi-lookup (ag/find-ifi (:agent params))]
          ;; TODO: extract this fn
          (get-in @state
                  [:state/agents
                   ifi-lookup]
                  (ag/person (:agent params)))))
      p/ActivityInfoResource
      (-get-activity [_ params]
        (get-in @state
                [:state/activities
                 (:activityId params)]))
      DumpableMemoryLRS
      (dump [_]
        @state))))

(defn fixture-state
  "Get the state of a post-conformance test lrs from file."
  []
  (-> (read-string (slurp (io/resource "lrs/state.edn")))
      (update :state/statements (partial conj (ss/statements-priority-map)))
      (update :state/attachments
              #(reduce-kv (fn [m sha2 a]
                            (assoc m sha2
                                   (update a :content byte-array)))
                         {}
                         %))
      (update
       :state/documents
       (fn [docs]
         (into {}
               (for [[ctx-key docs-map] docs]
                 [ctx-key (into (doc/documents-priority-map)
                                (for [[doc-id doc] docs-map]
                                  [doc-id (update doc :contents byte-array)]))]))))))

(s/fdef fixture-state
        :args (s/cat)
        :ret ::state)


(s/def ::xapi-path-prefix
  string?)

(s/def ::statements-result-max
  pos-int?)

(s/def ::init-state ::state)

(s/fdef new-lrs
        :args (s/cat :options
                     (s/keys :opt-un [::xapi-path-prefix
                                      ::statements-result-max
                                      ::init-state]))
        :ret ::p/statements-resource-instance)