(ns com.yetanalytics.lrs.impl.memory
  "A naive LRS implementation in memory"
  (:require [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.xapi.statements           :as ss]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]
            [com.yetanalytics.lrs.xapi.agents     :as ag]
            [com.yetanalytics.lrs.xapi.activities :as ac]
            [com.yetanalytics.lrs.xapi.document   :as doc]
            [com.yetanalytics.lrs.util      :refer [form-encode json-string]]
            [com.yetanalytics.lrs.util.hash :refer [sha-1]]
            [clojure.spec.alpha     :as s :include-macros true]
            [clojure.spec.gen.alpha :as sgen :include-macros true]
            [xapi-schema.spec       :as xs]
            [clojure.core.async :as a :include-macros true]
            #?@(:clj [[clojure.java.io :as io]]
                :cljs [[cljs.nodejs] ; special require for compilation
                       [fs]
                       [tmp]
                       [cljs.reader :refer [read-string]]]))
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [java.nio.file Files Paths]))
  #?(:cljs (:require-macros [com.yetanalytics.lrs.impl.memory
                             :refer [reify-sync-lrs
                                     reify-async-lrs
                                     reify-both-lrs]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State
;; Implemented as an atom; it is located in the `new-lrs` fn at the very bottom
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; State
(s/def :state/statements
  ::ss/statements-priority-map)

(s/def :state/voided-statements
  (s/map-of :statement/id
            ::xs/lrs-statement))

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
  {:state/statements        (ss/statements-priority-map)
   :state/voided-statements {}
   :state/refs              {}
   :state/activities        {}
   :state/agents            {}
   :state/attachments       {}
   :state/documents         {}})

(s/fdef empty-state
  :args (s/cat)
  :ret ::state)

(defprotocol DumpableMemoryLRS
  (dump [_] "Return the LRS's state in EDN"))

;; Fixture state (for dev)

(defn load-fixture [resource-path]
  #?(:clj (read-string (slurp (io/resource resource-path)))
     :cljs (read-string (.readFileSync fs
                                       (str "dev-resources/" resource-path)
                                       #js {:encoding "UTF-8"}))))

(defn- attachment->byte-arr
  [att]
  #?(:clj (update att :content byte-array)
     :cljs (assoc att :content (let [f (.fileSync tmp)
                                     c (:content att)]
                                 (.writeFileSync
                                  fs
                                  (.-name f)
                                  (-> js/String
                                      .-fromCharCode
                                      (.apply nil (clj->js c))))
                                 f))))

(defn- document->byte-arr
  [doc]
  #?(:clj (update doc :contents byte-array)
     :cljs (assoc doc :contents (let [c (:contents doc)]
                                  (-> js/String
                                      .-fromCharCode
                                      (.apply nil (clj->js c)))))))

(defn- coerce-state
  [state]
  (-> state
      (update :state/statements
              (partial conj (ss/statements-priority-map)))
      (update :state/attachments
              #(reduce-kv
                (fn [m sha2 att]
                  (assoc m sha2 (attachment->byte-arr att)))
                {}
                %))
      (update :state/documents
              (fn [docs]
                (into {}
                      (for [[ctx-key docs-map] docs]
                        [ctx-key
                         (into
                          (doc/documents-priority-map)
                          (for [[doc-id doc] docs-map]
                            [doc-id (document->byte-arr doc)]))]))))))

(defn load-state [resource-path]
  (-> (load-fixture resource-path)
      coerce-state))

(defn fixture-state*
  "Get the state of a post-conformance test lrs from file."
  []
  (load-state "lrs/state.edn"))

(def fixture-state (memoize fixture-state*))

(s/fdef fixture-state
  :args (s/cat)
  :ret ::state)

#?(:clj (defn- file->byte-vec
          [^java.io.File f]
          (let [ba (Files/readAllBytes (.toPath f))]
            (vec (map #(bit-and % 0xFF) ^bytes ba))))
   :cljs (defn file->byte-vec
           [fd]
           (let [buf (.readFileSync fs fd)]
             (vec (js/Uint8Array. buf)))))

(defn safe-state
  "Convert byte arrays and files to vectors for storage."
  [state]
  (-> state
      (update :state/attachments
              #(reduce-kv
                (fn [m sha2 att]
                  (assoc m sha2 (update att :content file->byte-vec)))
                {}
                %))
      (update :state/documents
              (fn [docs]
                (into {}
                      (for [[ctx-key docs-map] docs]
                        [ctx-key
                         (into
                          {}
                          (for [[doc-id doc] docs-map]
                            [doc-id (update doc :contents vec)]))]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Activities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;; Operations on in-mem state

;; clj-kondo incorrectly flags this as unused since it is only called in
;; clj macro code

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-activity
  [state params]
  {:activity
   (get-in @state
           [:state/activities
            (:activityId params)])})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Agents
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;; Operations on in-mem state

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-person
  [state params]
  {:person
   (let [ifi-lookup (ag/find-ifi (:agent params))]
     ;; TODO: extract this fn
     (get-in @state
             [:state/agents
              ifi-lookup]
             (ag/person (:agent params))))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; StatementRefs

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

;; Attachments

(s/def :state/attachments
  ;; track attachments
  (s/map-of :attachment/sha2
            ::ss/attachment))

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

;; Insertion

(defn- on-statement-conflict
  [state extant statement]
  (if (ss/statements-immut-equal? extant statement)
    state ;; No change to LRS
    (p/throw-statement-conflict statement extant)))

(defn- insert-voiding-statement
  [state statement stmt-id ?ref-target-id]
  (if-let [void-target-id ?ref-target-id]
    (if-let [extant-target
             (get-in state [:state/statements void-target-id])]
      (if (ss/voiding-statement? extant-target)
        ;; Can't void a voiding statement
        (p/throw-invalid-voiding-statement statement)
        ;; Void statement
        (-> state
            (assoc-in [:state/statements stmt-id] statement)
            (update :state/statements dissoc void-target-id)
            (assoc-in [:state/voided-statements void-target-id]
                      extant-target)))
      (assoc-in state [:state/statements stmt-id] statement))
    (p/throw-invalid-voiding-statement statement)))

(defn- insert-non-voiding-statement
  [state statement stmt-id]
  (assoc-in state [:state/statements stmt-id] statement))

(defn- insert-statement
  [state statement stmt-id ?ref-target-id]
  (let [extant (get-in state [:state/statements stmt-id])]
    (cond
      extant
      (on-statement-conflict state extant statement)

      (ss/voiding-statement? statement)
      (insert-voiding-statement state statement stmt-id ?ref-target-id)

      :else
      (insert-non-voiding-statement state statement stmt-id))))

(defn transact-statements [lrs-state
                           new-statements
                           attachments]
  (letfn [(transact-stmt-fn
            [state statement]
            (let [stmt-id     (ss/normalize-id (get statement "id"))
                  ?ref-tgt-id (ss/statement-ref-id statement)]
              (cond-> (insert-statement state statement stmt-id ?ref-tgt-id)
                true        (update :state/activities
                                    store-activities
                                    (ss/statement-related-activities statement))
                true        (update :state/agents
                                    store-agents
                                    (ss/statement-agents statement true true))
                true        (update :state/attachments
                                    store-attachments
                                    attachments)
                ?ref-tgt-id (update :state/refs
                                    store-ref
                                    stmt-id
                                    ?ref-tgt-id))))]
    (reduce transact-stmt-fn
            lrs-state
            new-statements)))

(s/fdef transact-statements
  :args (s/cat :lrs-state ::state
               :statements ::xs/lrs-statements
               :attachments ::ss/attachments)
  :ret ::state)

(defn statements-seq
  "Returns a lazy seq of statements for the given params possibly from id from,
  w/o limit."
  [state
   {:keys [statementId
           voidedStatementId
           verb
           activity
           registration
           related_activities
           related_agents
           since
           until
           ascending
           agent
           from ; Like "Exclusive Start Key"
           ;; Unused
           _limit
           _attachments]
    format-type :format
    :or {related_activities false
         related_agents     false
         ascending          false
         format-type        "exact"}}
   ltags]
  (if (or statementId voidedStatementId)
    ;; Single statement query
    (if-let [result
             (cond
               statementId
               (let [stmt-id (ss/normalize-id statementId)]
                 (get-in state [:state/statements stmt-id]))
               voidedStatementId
               (let [voided-stmt-id (ss/normalize-id voidedStatementId)]
                 (get-in state [:state/voided-statements voided-stmt-id])))]
      ;; Results! Return the list of stmts
      (list (cond-> result
              (= "canonical" format-type)
              (ss/format-canonical ltags)
              (= "ids" format-type)
              ss/format-statement-ids))
      ;; No results! Return an empty list
      (list))
    ;; Multi-statement query
    (let [?since-stamp (some-> since timestamp/normalize)
          ?until-stamp (some-> until timestamp/normalize)]
      (cond->> (or #?(:cljs (map second (:state/statements state))
                      :clj (vals (:state/statements state)))
                   (list))
        ascending reverse

        ;; paging from
        from (drop-while #(not= from (get % "id")))
        from rest

        ;; since + until
        (and (not ascending) since)
        (take-while #(neg? (compare ?since-stamp (ss/stored-stamp %))))

        (and (not ascending) until)
        (drop-while #(neg? (compare ?until-stamp (ss/stored-stamp %))))

        (and ascending since)
        (drop-while #(not (neg? (compare ?since-stamp (ss/stored-stamp %)))))

        (and ascending until)
        (take-while #(not (pos? (compare (ss/stored-stamp %) ?until-stamp))))

        ;; simple filters
        verb
        (filter (fn filter-stmt-verb [s]
                  (or
                   ;; direct
                   (= verb (get-in s ["verb" "id"]))
                   ;; via reference
                   (and (ss/statement-ref? s)
                        (let [target-id (ss/statement-ref-id s)]
                          ;; non-void statement
                          (= verb (get-in state [:state/statements
                                                 target-id
                                                 "verb"
                                                 "id"]))
                          ;; voided statement
                          (= verb (get-in state [:state/voided-statements
                                                 target-id
                                                 "verb"
                                                 "id"])))))))
        registration
        (filter (fn filter-stmt-reg [s]
                  (let [normalized-reg (ss/normalize-id registration)]
                    (= normalized-reg
                       (some-> (get-in s ["context" "registration"])
                               ss/normalize-id)))))

        ;; complex filters
        activity
        (filter (if related_activities
                  ;; complex activity-filter
                  (fn [s]
                    (some (partial = activity)
                          (ss/statement-related-activity-ids s)))
                  ;; simple activity filter
                  #(= activity (get-in % ["object" "id"]))))

        agent
        (filter (let [agents-fn (if related_agents
                                  #(ss/statement-agents % true)
                                  #(ss/statement-agents % false))]
                  (fn [s]
                    (some (partial ag/ifi-match? agent)
                          (agents-fn s)))))

        ;; formatting
        (= format-type "canonical")
        (map #(ss/format-canonical % ltags))

        (= format-type "ids")
        (map ss/format-statement-ids)))))

(s/fdef statements-seq
  :args (s/cat :state ::state
               :params :xapi.statements.GET.request/params
               :ltags (s/coll-of ::xs/language-tag))
  :ret (s/coll-of ::xs/lrs-statement))

;; internal impls used in sync/async specifically where indicated

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- store-statements-sync
  [ctx state statements attachments]
  (try (let [{version :com.yetanalytics.lrs/version
              :or     {version "1.0.3"}}
             ctx
             prepared-statements
             (map
              (fn [s stamp]
                (ss/prepare-statement
                 (assoc s
                        "stored" stamp
                        "version" (or (get s "version")
                                      (case version
                                        "1.0.3" "1.0.0"
                                        "2.0.0" "2.0.0")))))
              statements
              (timestamp/stamp-seq))]
         (swap! state
                transact-statements
                prepared-statements
                attachments)
         {:statement-ids
          (into []
                (map #(ss/normalize-id (get % "id"))
                     prepared-statements))})
       (catch #?(:clj clojure.lang.ExceptionInfo
                 :cljs ExceptionInfo) exi
         {:error exi})))

(defn- get-attachments
  [state statements]
  (keep (:state/attachments @state)
        (ss/all-attachment-hashes statements)))

(defn- make-more-url
  [xapi-path-prefix params last-id agent]
  (str xapi-path-prefix
       "/statements?"
       (form-encode
        (cond-> params
          true (assoc :from last-id)
          ;; Re-encode the agent if present
          agent (assoc :agent (json-string agent))))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-statements-sync
  [state
   xapi-path-prefix
   {:keys [statementId
           voidedStatementId
           limit
           attachments
           agent
           ;; Unused - handled in `statements-seq`
           _verb
           _activity
           _registration
           _related_activities
           _related_agents
           _since
           _until
           _ascending
           _page
           _from ; Like "Exclusive Start Key"
           ]
    :as params
    :or {limit       50
         attachments false}}
   ltags]
  (let [results (statements-seq @state params ltags)]
    (if (or statementId voidedStatementId)
      ;; Single statement
      (let [statement (first results)]
        (cond-> {}
          statement
          (assoc :statement statement)

          (and statement attachments)
          (assoc :attachments
                 (into [] (get-attachments state [statement])))))
      ;; Multiple statements, via a paged sequential query
      (let [[statements rest-results]
            (cond->> results
              (< 0 limit) (split-at limit) ; paging to
              (= 0 limit) vector)          ; mock the split
            more?
            (some? (first rest-results))
            statement-result
            (cond-> {:statements statements}
              more?
              (assoc :more
                     (make-more-url
                      xapi-path-prefix
                      params
                      (-> statements last (get "id") ss/normalize-id)
                      agent)))
            attachments
            (into [] (when attachments
                       (get-attachments state statements)))]
        {:statement-result statement-result
         :attachments      attachments}))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-statements-async
  [state
   xapi-path-prefix
   {:keys [statementId
           voidedStatementId
           limit
           attachments
           agent
           ;; Unused - handled in `statements-seq`
           _verb
           _activity
           _registration
           _related_activities
           _related_agents
           _since
           _until
           _ascending
           _page
           _from ; Like "Exclusive Start Key"
           ]
    :as params
    :or {limit       50
         attachments false}}
   ltags]
  (let [single?     (or statementId voidedStatementId)
        result-chan (a/chan)]
    (a/go
      ;; write the first header
      (a/>! result-chan (if single?
                          :statement
                          :statements))
      (loop [results           (statements-seq @state params ltags)
             last-id            nil
             result-count       0
             result-attachments {}]
        (if (and (< 0 limit)
                 (= result-count limit)
                 (first results))
          ;; all results returned, maybe process more
          (do
            (a/>! result-chan :more)
            (a/>! result-chan (make-more-url
                               xapi-path-prefix
                               params
                               last-id
                               agent))
            (recur (list)
                   nil
                   0
                   result-attachments))
          (if-let [statement (first results)]
            (do
              (a/>! result-chan statement)
              (recur (rest results)
                     (ss/normalize-id (get statement "id"))
                     (inc result-count)
                     ;; deduplicate attachments
                     (merge result-attachments
                            (select-keys
                             (:state/attachments @state)
                             (ss/all-attachment-hashes [statement])))))
            (when attachments
              (a/>! result-chan :attachments)
              (doseq [att (vals result-attachments)]
                (a/>! result-chan att))))))
      (a/close! result-chan))
    result-chan))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Documents
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state-tuple-re-spec
  (s/cat :activity-id :activity/id
         :agent ::xs/agent
         :registration (s/? :context/registration)))

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
                                 _stateId
                                 _profileId
                                 registration
                                 _since] :as params}]
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

(defn contents->byte-array
  [contents]
  #?(:clj (with-open [is (io/input-stream contents)]
            (let [buf (ByteArrayOutputStream.)]
              (io/copy is buf)
              (.flush buf)
              (.toByteArray buf)))
     ;; Itsa no-op
     :cljs contents))

(defn init-document
  [{:keys [contents content-type content-length]}]
  (let [contents-bytes (contents->byte-array contents)]
    {:contents contents-bytes
     :content-type (or content-type "application/octet-stream")
     :content-length (or content-length (count contents-bytes))}))

(defn transact-document
  [documents params document merge?]
  (let [{:keys [context-key
                document-key
                document]}
        (document-keys
         params
         (init-document document))
        update-doc-fn
        (if merge?
          (update-in documents
                     [context-key document-key]
                     doc/merge-or-replace
                     document)
          (assoc-in documents
                    [context-key document-key]
                    (assoc document :updated (doc/updated-stamp-now))))]
    (update update-doc-fn
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
         {?since :since} :query} (param-keys-query params)
        ?since-inst (some-> ?since timestamp/normalize)]
    (cond->> (some-> (get-in state [:state/documents context-key])
                     #?(:clj vals
                        :cljs (->> (map second))))
      ?since (drop-while
              (fn [doc]
                (< -1 (compare ?since-inst (doc/updated-stamp doc)))))
      true   (mapv :id))))

(s/fdef get-document-ids
  :args (s/cat :state ::state
               :params ::p/get-document-ids-params)
  :ret (s/coll-of ::doc/id))

(defn delete-document
  [documents params]
  (let [{:keys [context-key
                document-key]}
        (param-keys params)]
    (update documents
            context-key
            (fnil dissoc (doc/documents-priority-map))
            document-key)))

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


;; Operations on in-mem state.
;; Shorten document to doc here to avoid conflicts with existing

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- set-doc
  [state params document merge?]
  (try (swap! state
              update
              :state/documents
              transact-document
              params
              document
              merge?)
       {}
       (catch #?(:clj clojure.lang.ExceptionInfo
                 :cljs ExceptionInfo) exi
         {:error exi})))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-doc
  [state params]
  {:document (get-document @state params)})

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-doc-ids
  [state params]
  {:document-ids (get-document-ids @state params)})

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- delete-doc
  [state params]
  (swap! state update :state/documents delete-document params)
  {})

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- delete-docs
  [state params]
  (swap! state update :state/documents delete-documents params)
  {})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authenticate + Authorize
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- authenticate
  [_state _lrs _ctx]
  ;; Authenticate is a no-op right now, just returns a dummy
  {:result
   {:scopes #{:scope/all}
    :prefix ""
    :auth   {:no-op {}}}})

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- authorize
  [_state _lrs _ctx _auth-identity]
  ;; Auth, also a no-op right now
  {:result true})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Misc
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- get-about
  [state]
  {:etag (sha-1 @state)
   :body {:version ["1.0.0"
                    "1.0.1"
                    "1.0.2"
                    "1.0.3"
                    "2.0.0"]}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Putting It All Together
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (def common-lrs-input
     '(`~DumpableMemoryLRS
       (dump [_]
         (deref state)))))

#?(:clj
   (def sync-lrs-input
     '(`~p/AboutResource
       (-get-about [_ _ _]
         (get-about state))
       `~p/StatementsResource
       (-store-statements [_ ctx _ statements attachments]
         (store-statements-sync ctx state statements attachments))
       (-get-statements [_ _ _ params ltags]
         (get-statements-sync state xapi-path-prefix params ltags))
       (-consistent-through [_ _ _]
         (ss/now-stamp))
       `~p/DocumentResource
       (-set-document [lrs _ _ params document merge?]
         (set-doc state params document merge?))
       (-get-document [_ _ _ params]
         (get-doc state params))
       (-get-document-ids [_ _ _ params]
         (get-doc-ids state params))
       (-delete-document [lrs _ _ params]
         (delete-doc state params))
       (-delete-documents [lrs _ _ params]
         (delete-docs state params))
       `~p/AgentInfoResource
       (-get-person [_ _ _ params]
         (get-person state params))
       `~p/ActivityInfoResource
       (-get-activity [_ _ _ params]
         (get-activity state params))
       `~p/LRSAuth
       (-authenticate [lrs ctx]
         (authenticate state lrs ctx))
       (-authorize [lrs ctx auth-identity]
         (authorize state lrs ctx auth-identity)))))

#?(:clj
   (def async-lrs-input
     '(`~p/AboutResourceAsync
       (-get-about-async [lrs _ auth-identity]
         (a/go (get-about state)))
       `~p/StatementsResourceAsync
       (-store-statements-async [lrs ctx auth-identity stmts attachments]
         (a/go (store-statements-sync ctx state stmts attachments)))
       (-get-statements-async [_ _ _ params ltags]
         (get-statements-async state xapi-path-prefix params ltags))
       (-consistent-through-async [_ _ _]
         (a/go (ss/now-stamp)))
       `~p/DocumentResourceAsync
       (-set-document-async [lrs _ auth-identity params doc merge?]
         (a/go (set-doc state params doc merge?)))
       (-get-document-async [lrs _ auth-identity params]
         (a/go (get-doc state params)))
       (-get-document-ids-async [lrs _ auth-identity params]
         (a/go (get-doc-ids state params)))
       (-delete-document-async [lrs _ auth-identity params]
         (a/go (delete-doc state params)))
       (-delete-documents-async [lrs _ auth-identity params]
         (a/go (delete-docs state params)))
       `~p/AgentInfoResourceAsync
       (-get-person-async [lrs _ auth-identity params]
         (a/go (get-person state params)))
       `~p/ActivityInfoResourceAsync
       (-get-activity-async [lrs _ auth-identity params]
         (a/go (get-activity state params)))
       `~p/LRSAuthAsync
       (-authenticate-async [lrs ctx]
         (a/go (authenticate state lrs ctx)))
       (-authorize-async [lrs ctx auth-identity]
         (a/go (authorize state lrs ctx auth-identity))))))

#?(:clj
   (defmacro reify-sync-lrs
     []
     `(reify ~@(concat sync-lrs-input common-lrs-input))))

#?(:clj
   (defmacro reify-async-lrs
     []
     `(reify ~@(concat async-lrs-input common-lrs-input))))

#?(:clj
   (defmacro reify-both-lrs
     []
     `(reify ~@(concat sync-lrs-input async-lrs-input common-lrs-input))))

;; clj-kondo incorrectly flags `xapi-path-prefix` and `state` as unused
;; since they are called from within macros.

#_{:clj-kondo/ignore [:unused-binding]}
(defn new-lrs [{:keys [xapi-path-prefix
                       _statements-result-max
                       init-state
                       mode] ;; mode can be :sync or :async
                :or {xapi-path-prefix       "/xapi"
                     init-state             (empty-state)
                     mode                   :both}}]
  (let [valid-state? (fn [s]
                       ;; Use more permissive 2.0.0 spec
                       (binding [xs/*xapi-version* "2.0.0"]
                         (if (s/valid? ::state s)
                           true
                           (do
                             (println "\n Invalid Memory LRS State\n\n")
                             (s/explain ::state s)
                             false))))
        state        (atom init-state
                           :validator
                           valid-state?)]
    (case mode
      :sync  (reify-sync-lrs)
      :async (reify-async-lrs)
      :both  (reify-both-lrs))))

;; Helper specs

(s/def ::xapi-path-prefix
  string?)

(s/def ::statements-result-max
  pos-int?)

(s/def ::mode
  #{:sync :async :both})

(s/def ::init-state ::state)

;; LRS specs

(s/def ::lrs-sync
  (s/with-gen ::p/lrs
    (fn []
      (sgen/return (new-lrs {:init-state (fixture-state)
                             :mode :sync})))))

(s/def ::lrs-async
  (s/with-gen ::p/lrs-async
    (fn []
      (sgen/return (new-lrs {:init-state (fixture-state)
                             :mode :async})))))

(s/def ::lrs-both
  (s/with-gen (s/and ::p/lrs
                     ::p/lrs-async)
    (fn []
      (sgen/return (new-lrs {:init-state (fixture-state)
                             :mode :both})))))

(s/def ::lrs
  (s/or :sync ::lrs-sync
        :async ::lrs-async
        :both ::lrs-both))

(s/fdef new-lrs
  :args (s/cat :options
               (s/keys :opt-un [::xapi-path-prefix
                                ::statements-result-max
                                ::init-state
                                ::mode]))
  :ret ::lrs)
