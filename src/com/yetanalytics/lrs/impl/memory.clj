(ns com.yetanalytics.lrs.impl.memory
  "A naive LRS implementation in memory"
  (:require [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.agents :as ag]
            [com.yetanalytics.lrs.xapi.activities :as ac]
            [com.yetanalytics.lrs.xapi.document :as doc]
            [com.yetanalytics.lrs.util.hash :refer [sha-1]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [clojure.data.priority-map :as pm]
            [clojure.data.json :as json]
            [clojure.walk :as w]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [ring.util.codec :as codec]
            [clojure.core.async :as a]
            ))

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
               :refs-map :state/refs
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
        :args (s/cat :state ::state
                     :params ::p/get-document-params)
        :ret (s/nilable :com.yetanalytics.lrs.xapi/document))

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
    (update documents context-key (fnil dissoc (doc/documents-priority-map)) document-key)))

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

(defn statements-seq
  "Returns a lazy seq of statements for the given params possibly from id from,
  w/o limit."
  [state {:keys [statementId
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
                 agent
                 from ;; Like "Exclusive Start Key"
                 ]
          format-type :format
          :as params
          :or {related_activities false
               related_agents false
               limit 50
               attachments false
               ascending false
               format-type "exact"}} ltags]
  (if (or statementId voidedStatementId)
    (if-let [result (cond
                      statementId
                      (get-in state [:state/statements statementId])
                      voidedStatementId
                      (get-in state [:state/voided-statements voidedStatementId]))]
      (list (cond-> result
              (= "canonical" format-type)
              (ss/format-canonical ltags)
              (= "ids" format-type)
              ss/format-statement-ids))
      (list))
    (cond->> (or (vals (:state/statements state))
                 (list))
      ascending reverse
      ;; paging from
      from (drop-while #(not= from
                              (get % "id")))
      from rest
      (and
       (not ascending)
       since) (drop-while #(< -1 (compare since (get % "stored"))))
      (and
       (not ascending)
       until)
      (take-while #(< -1 (compare until (get % "stored"))))
      (and
       ascending
       until) (drop-while #(< -1 (compare until (get % "stored"))))
      (and
       ascending
       since) (take-while #(< -1 (compare since (get % "stored"))))

      ;; simple filters
      verb (filter #(or
                     ;; direct
                     (= verb (get-in % ["verb" "id"]))
                     ;; via reference
                     (and (ss/statement-ref? %)
                          (let [ref-id (get % "id")
                                target-id (ss/statement-ref-id %)]
                            ;; non-void statement
                            (= verb (get-in state [:state/statements
                                                   target-id
                                                   "verb"
                                                   "id"]))
                            (= verb (get-in state [:state/voided-statements
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
      ;; Formatting
      (= format-type
         "canonical") (map #(ss/format-canonical % ltags))
      (= format-type
         "ids")       (map ss/format-statement-ids)
      )))

(s/fdef statements-seq
        :args (s/cat :state ::state
                     :params :xapi.statements.GET.request/params
                     :ltags (s/coll-of ::xs/language-tag))
        :ret (s/coll-of ::xs/lrs-statement))

(defn fixture-state*
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

(def fixture-state (memoize fixture-state*))

(s/fdef fixture-state
        :args (s/cat)
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
        {:etag (sha-1 @state)
         :body {:version ["1.0.0",
                          "1.0.1",
                          "1.0.2",
                          "1.0.3"]}})
      p/AboutResourceAsync
      (-get-about-async [lrs]
        (a/go
          (p/-get-about lrs)))
      p/StatementsResource
      (-store-statements [_ statements attachments]
        (try (let [prepared-statements (map ss/prepare-statement
                                            statements)]
               (swap! state transact-statements prepared-statements attachments)
               {:statement-ids
                (into []
                      (map #(get % "id")
                           prepared-statements))})
             (catch clojure.lang.ExceptionInfo exi
               {:error exi})))
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
                                  agent
                                  from ;; Like "Exclusive Start Key"
                                  ]
                           format-type :format
                           :as params
                           :or {related_activities false
                                related_agents false
                                limit 50
                                attachments false
                                ascending false
                                page 0
                                format-type "exact"}} ltags]
        (let [results (statements-seq @state params ltags)]
          (if (or statementId voidedStatementId) ;; single statement
            (let [statement (first results)]
              (cond-> {}
                statement (assoc :statement
                                 statement)
                attachments (assoc :attachments
                                   (into []
                                         (keep (:state/attachments @state)
                                               (ss/all-attachment-hashes [statement]))))))
            ;; otherwise, this is a paged sequential query
            (let [[statements rest-results]
                  (cond->> results
                    ;; paging to
                    (< 0 limit) (split-at limit)
                    ;; mock the split
                    (= 0 limit) vector)
                  more? (some? (first rest-results))

                  statement-result (cond-> {:statements
                                            statements}
                                     more? (assoc :more
                                                  (str xapi-path-prefix
                                                       "/xapi/statements?"
                                                       (codec/form-encode
                                                        (cond-> (assoc params :from
                                                                       (-> statements
                                                                           last
                                                                           (get "id")))
                                                          ;; Re-encode the agent if present
                                                          agent (assoc :agent (json/write-str agent)))))))]
              {:statement-result statement-result
               :attachments (into []
                                  (when attachments
                                    (keep
                                     (:state/attachments @state)
                                     (ss/all-attachment-hashes statements))))}))))
      p/StatementsResourceAsync
      (-store-statements-async [lrs statements attachments]
        (a/go
          (p/-store-statements lrs statements attachments)))
      (-get-statements-async [_ {:keys [statementId
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
                                  agent
                                  from ;; Like "Exclusive Start Key"
                                  ]
                           format-type :format
                           :as params
                           :or {related_activities false
                                related_agents false
                                limit 50
                                attachments false
                                ascending false
                                page 0
                                format-type "exact"}} ltags]
        (let [single? (or statementId voidedStatementId)
              result-chan (a/chan)]
          (a/go
            ;; write the first header
            (a/>! result-chan (if single?
                                :statement
                                :statements))
            (loop [results (statements-seq @state params ltags)
                   last-id nil
                   result-count 0
                   result-attachments (list)]
              (if ;; all results returned, maybe process more
                  (and (< 0 limit)
                       (= result-count
                          limit)
                       (first results))
                (do
                  (a/>! result-chan
                        :more)
                  (a/>! result-chan
                        (str xapi-path-prefix
                             "/xapi/statements?"
                             (codec/form-encode
                              (cond-> (assoc params :from
                                             last-id)
                                ;; Re-encode the agent if present
                                agent (assoc :agent (json/write-str agent))))))
                    (recur (list)
                           nil
                           0
                           result-attachments))
                (if-let [statement (first results)]
                  (do
                    (a/>! result-chan statement)
                    (recur (rest results)
                           (get statement "id")
                           (inc result-count)
                           (into result-attachments
                                 (when attachments
                                   (keep
                                    (:state/attachments @state)
                                    (ss/all-attachment-hashes [statement]))))))
                  (when attachments
                    (a/>! result-chan :attachments)
                    (doseq [att result-attachments]
                      (a/>! result-chan att))))))
            (a/close! result-chan))
          result-chan))
      p/DocumentResource
      (-set-document [lrs params document merge?]
        (try (swap! state update :state/documents transact-document params document merge?)
             nil
             (catch clojure.lang.ExceptionInfo exi
               {:error exi})))
      (-get-document [_ params]
        {:document (get-document @state params)})
      (-get-document-ids [_ params]
        {:document-ids (get-document-ids @state params)})
      (-delete-document [lrs params]
        (swap! state update :state/documents delete-document params)
        nil)
      (-delete-documents [lrs params]
        (swap! state update :state/documents delete-documents params)
        nil)
      p/DocumentResourceAsync
      (-set-document-async [lrs params document merge?]
        (a/go
          (p/-set-document lrs params document merge?)))
      (-get-document-async [lrs params]
        (a/go
          (p/-get-document lrs params)))
      (-get-document-ids-async [lrs params]
        (a/go
          (p/-get-document-ids lrs params)))
      (-delete-document-async [lrs params]
        (a/go
          (p/-delete-document lrs params)))
      (-delete-documents-async [lrs params]
        (a/go
          (p/-delete-documents lrs params)))
      p/AgentInfoResource
      (-get-person [_ params]
        {:person
         (let [ifi-lookup (ag/find-ifi (:agent params))]
           ;; TODO: extract this fn
           (get-in @state
                   [:state/agents
                    ifi-lookup]
                   (ag/person (:agent params))))})
      p/AgentInfoResourceAsync
      (-get-person-async [lrs params]
        (a/go
          (p/-get-person lrs params)))
      p/ActivityInfoResource
      (-get-activity [_ params]
        {:activity (get-in @state
                           [:state/activities
                            (:activityId params)])})
      p/ActivityInfoResourceAsync
      (-get-activity-async [lrs params]
        (a/go
          (p/-get-activity lrs params)))
      DumpableMemoryLRS
      (dump [_]
        @state))))

(s/def ::xapi-path-prefix
  string?)

(s/def ::statements-result-max
  pos-int?)

(s/def ::init-state ::state)

(s/def ::lrs
  (s/with-gen ::p/lrs
    (fn []
      (sgen/return (new-lrs {:init-state (fixture-state)})))))

(s/fdef new-lrs
        :args (s/cat :options
                     (s/keys :opt-un [::xapi-path-prefix
                                      ::statements-result-max
                                      ::init-state]))
        :ret ::lrs)


(comment
  (clojure.repl/doc a/pipe)


  )
