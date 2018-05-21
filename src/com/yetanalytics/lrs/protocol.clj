(ns com.yetanalytics.lrs.protocol
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec.resources :as xsr]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.lrs.xapi :as xapi]
            [com.yetanalytics.lrs.spec.common :as sc]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(set! *warn-on-reflection* true)

(s/def :ret/error
  (s/with-gen
    #(instance? clojure.lang.ExceptionInfo %)
    (fn []
      (sgen/fmap ex-info
                 (sgen/tuple
                  (sgen/string-ascii)
                  (s/gen map?))))))

(s/def ::error-ret
  (s/keys :req-un [:ret/error]))

;; About
;; /xapi/about

(defprotocol AboutResource
  "Protocol for retrieving LRS info"
  (-get-about [this]
    "Return information about the LRS"))

(defn about-resource?
  [lrs]
  (satisfies? AboutResource lrs))

(s/def ::about-resource-instance
  about-resource?)

(s/def ::get-about-ret
  (s/keys
   :req-un [:xapi.about.GET.response/body]
   :opt-un [::xapi/etag]))

(defprotocol AboutResourceAsync
  "AsyncProtocol for retrieving LRS info."
  (-get-about-async [this]
    "Return information about the LRS. Returns response in a promise channel."))

(defn about-resource-async?
  [lrs]
  (satisfies? AboutResourceAsync lrs))

(s/def ::about-resource-async-instance
  about-resource-async?)

(s/def ::get-about-asyc-ret
  (sc/from-port
   (s/keys
    :req-un [:xapi.about.GET.response/body]
    :opt-un [::xapi/etag])))

;; Document APIs

(defprotocol DocumentResource
  "Protocol for storing/retrieving documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#23-state-resource"
  (-set-document [this params document merge?]
    "Store a document, merges.")
  (-get-document [this params]
    "Get a single document.")
  (-get-document-ids [this params]
    "Get multiple document ids.")
  (-delete-document [this params]
    "Delete a single document.")
  (-delete-documents [this params]
    "Delete multiple documents."))

(defn document-resource? [lrs]
  (satisfies? DocumentResource lrs))

(s/def ::document-resource-instance
  document-resource?)

(s/def ::set-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

(s/def ::set-document-ret
  (s/or :success nil?
        :error ::error-ret))

(s/def ::get-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

(s/def :get-document-ret/document
  (s/nilable :com.yetanalytics.lrs.xapi/document))

(s/def ::get-document-ret
  (s/keys :opt-un [::xapi/etag
                   :get-document-ret/document]))

(s/def ::get-document-ids-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/query-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/query-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/query-params)))

(s/def :get-document-ids-ret/document-ids
  (s/coll-of ::doc/id
             :kind vector?
             :into []))

(s/def ::get-document-ids-ret
  (s/keys :opt-un [::xapi/etag
                   :get-document-ids-ret/document-ids]))

(s/def ::get-document-all-params
  (s/or :single
        ::get-document-params
        :multiple
        ::get-document-ids-params))

(s/def ::delete-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

(s/def ::delete-document-ret
  nil?)

(s/def ::delete-documents-params
  (s/or :state (sc/with-conform-gen :xapi.document.state/context-params)))

(s/def ::delete-documents-ret
  nil?)

(s/def ::delete-document-all-params
  (s/or :single
        ::get-document-params
        :multiple
        ::delete-documents-params))

(defprotocol DocumentResourceAsync
  "Async protocol for storing/retrieving documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#23-state-resource"
  (-set-document-async [this params document merge?]
    "Store a document, merges. Returns a promise channel")
  (-get-document-async [this params]
    "Get a single document. Returns a promise channel")
  (-get-document-ids-async [this params]
    "Get multiple document ids.
     Returns a channel which will receive document ids and close.")
  (-delete-document-async [this params]
    "Delete a single document. Returns a promise channel.")
  (-delete-documents-async [this params]
    "Delete multiple documents. Returns a promise channel."))

(defn document-resource-async? [lrs]
  (satisfies? DocumentResourceAsync lrs))

(s/def ::document-resource-async-instance
  document-resource-async?)

(s/def ::set-document-async-ret
  (sc/from-port
   (s/or :success nil?
         :error ::error-ret)))

(s/def ::get-document-async-ret
  (sc/from-port
   (s/keys :opt-un [::xapi/etag
                    :get-document-ret/document])))

(s/def ::get-document-ids-async-ret
  (sc/from-port
   (s/keys :opt-un [::xapi/etag
                    :get-document-ids-ret/document-ids])))

(s/def ::delete-document-async-ret
  (sc/from-port
   nil?))

(s/def ::delete-documents-async-ret
  (sc/from-port
   nil?))

;; Activities
;; /xapi/activities
(defprotocol ActivityInfoResource
  "Protocol for retrieving activity info."
  (-get-activity [this params]
    "Get an xapi activity object."))

(defn activity-info-resource? [lrs]
  (satisfies? ActivityInfoResource lrs))

(s/def ::activity-info-resource-instance
  activity-info-resource?)

(s/def :get-activity-ret/activity
  (s/nilable ::xs/activity))

(s/def ::get-activity-ret
  (s/keys :opt-un [::xapi/etag
                   :get-activity-ret/activity]))

(defprotocol ActivityInfoResourceAsync
  "Async protocol for retrieving activity info."
  (-get-activity-async [this params]
    "Get an xapi activity object. Returns a promise channel."))

(defn activity-info-resource-async? [lrs]
  (satisfies? ActivityInfoResourceAsync lrs))

(s/def ::activity-info-resource-async-instance
  activity-info-resource-async?)

(s/def ::get-activity-async-ret
  (sc/from-port
   (s/keys :opt-un [::xapi/etag
                    :get-activity-ret/activity])))

;; Agents
;; /xapi/agents

(defprotocol AgentInfoResource
  "Protocol for retrieving information on agents."
  (-get-person [this params]
    "Get an xapi person object. Throws only on invalid params."))

(defn agent-info-resource?
  [lrs]
  (satisfies? AgentInfoResource lrs))

(s/def ::agent-info-resource-instance
  agent-info-resource?)

(s/def ::get-person-params
  (sc/with-conform-gen :xapi.agents.GET.request/params))

(s/def ::get-person-ret
  (s/keys
   :req-un [:xapi.agents.GET.response/person]
   :opt-un [::xapi/etag]))

(defprotocol AgentInfoResourceAsync
  "Async protocol for retrieving information on agents."
  (-get-person-async [this params]
    "Get an xapi person object. Throws only on invalid params."))

(defn agent-info-resource-async?
  [lrs]
  (satisfies? AgentInfoResourceAsync lrs))

(s/def ::agent-info-resource-async-instance
  agent-info-resource-async?)

(s/def ::get-person-params
  (sc/with-conform-gen :xapi.agents.GET.request/params))

(s/def ::get-person-async-ret
  (sc/from-port
   (s/keys
    :req-un [:xapi.agents.GET.response/person]
    :opt-un [::xapi/etag])))

;; Statements
;; /xapi/statements

(defprotocol StatementsResource
  "Protocol for storing/retrieving statements, activities, agents."
  (-store-statements [this statements attachments]
    "Store and persist validated statements and possibly attachments in the LRS,
     throwing if there's a conflict, and returning a vector of IDs.
     It is expected that the ID param will be included in statements that are PUT.")
  (-get-statements [this params ltags]
    "Retrieve statement or statements from the LRS given params and optionally ltags.
     Returns a statement result object or a single statement."))

(defn statements-resource?
  [lrs]
  (satisfies? StatementsResource lrs))

(s/def ::statements-resource-instance
  statements-resource?)

(s/def ::get-statements-params
  (sc/with-conform-gen :xapi.statements.GET.request/params))

(s/def :store-statements-ret/statement-ids
  (s/coll-of :statement/id :kind vector? :into []))

(s/def ::store-statements-ret
  (s/or :success (s/keys :req-un [:store-statements-ret/statement-ids])
        :error ::error-ret))


(s/def ::get-statements-ret
  (s/or :not-found (s/map-of any? any? :count 0)
        :found (s/keys
                :opt-un [::xapi/etag]
                :req-un [(and (or
                               :xapi.statements.GET.response/statement-result
                               ::xs/statement)
                              ::ss/attachments)])))

(defn throw-statement-conflict [conflicting-statement
                                extant-statement]
  (throw (ex-info "Statement Conflict"
                  {:type ::statement-conflict
                   :statement conflicting-statement
                   :extant-statement extant-statement})))

(defn throw-invalid-voiding-statement [invalid-voiding-statement]
  (throw (ex-info "Invalid Voiding Statement"
                  {:type ::invalid-voiding-statement
                   :statement invalid-voiding-statement})))

(defprotocol StatementsResourceAsync
  "Async Protocol for storing/retrieving statements, activities, agents."
  (-store-statements-async [this statements attachments]
    "Store and persist validated statements and possibly attachments in the LRS,
     throwing if there's a conflict, and returning a channel of ids.
     It is expected that the ID param will be included in statements that are PUT.")
  (-get-statements-async [this params ltags]
    "Retrieve statement or statements from the LRS given params and optionally ltags.
     For singular params, returns a promise channel with a single statement.
     For multiple statements GET returns a channel that will get, in order:
     Statements (if present), a more link if one is appropriate, and possibly
     n attachments for the statements."))

(defn statements-resource-async?
  [lrs]
  (satisfies? StatementsResourceAsync lrs))

(s/def ::statements-resource-async-instance
  statements-resource-async?)

(s/def ::store-statements-async-ret
  (sc/from-port
   (s/or :success (s/keys :req-un [:store-statements-ret/statement-ids])
         :error ::error-ret)))

#_(s/def :get-statements-async-ret/statement-result-chan
  (sc/from-port-coll
   (s/cat :statements (s/* ::xs/statement)
          :more-link (s/? :xapi.statements.GET.response.statement-result/more))))

#_(s/def :get-statements-async-ret/attachments-chan
  (sc/from-port-coll
   (s/coll-of ::ss/attachment)))

(s/def ::get-statements-async-ret
  (sc/from-port-coll
   (s/cat :result
          (s/alt :s (s/cat :header #{:statement}
                           :statement (s/? ::xs/lrs-statement))
                 :ss (s/cat :statements-header #{:statements}
                            :statements (s/* ::xs/lrs-statement)
                            :more (s/? (s/cat :more-header #{:more}
                                              :more-link :xapi.statements.GET.response.statement-result/more))))
          :attachments
          (s/? (s/cat :header #{:attachments}
                      :attachments (s/* ::ss/attachment))))))

;; Auth
(defprotocol LRSAuth
  "Protocol for an authenticatable LRS"
  (-authenticate-credentials [this api-key api-key-secret]
    "Authenticate for access to the LRS with key/secret credentials.")
  (-authenticate-token [this token]
    "Authenticate to the LRS with a token."))

(s/def ::lrs-auth-instance
  #(satisfies? LRSAuth %))

(s/def ::api-key
  string?)

(s/def ::api-key-secret
  string?)

(s/def ::token
  string?)

;; Spec for the whole LRS
(s/def ::lrs ;; A minimum viable LRS
  (s/and ::about-resource-instance
         ::statements-resource-instance
         ::activity-info-resource-instance
         ::agent-info-resource-instance
         ::document-resource-instance
         ))
