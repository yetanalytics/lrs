(ns com.yetanalytics.lrs.protocol
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.lrs.xapi :as xapi]
            [com.yetanalytics.lrs.spec.common :as sc]
            [com.yetanalytics.lrs.xapi.document :as doc]))

(set! *warn-on-reflection* true)

;; About
;; /xapi/about

(defprotocol AboutResource
  "Protocol for retrieving LRS info"
  (-get-about [this]
    "Return information about the LRS"))

(s/def ::about-resource-instance
  #(satisfies? AboutResource %))

(s/def ::get-about-ret
  (s/keys
   :req-un [:xapi.about.GET.response/body]
   :opt-un [::xapi/etag]))

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

(s/def ::document-resource-instance
  #(satisfies? DocumentResource %))

(s/def ::set-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

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

(s/def ::delete-documents-params
  (s/or :state (sc/with-conform-gen :xapi.document.state/context-params)))

(s/def ::delete-document-all-params
  (s/or :single
        ::get-document-params
        :multiple
        ::delete-documents-params))

;; Activities
;; /xapi/activities
(defprotocol ActivityInfoResource
  "Protocol for retrieving activity info."
  (-get-activity [this params]
    "Get an xapi activity object."))

(s/def ::activity-info-resource-instance
  #(satisfies? ActivityInfoResource %))

(s/def :get-activity-ret/activity
  (s/nilable ::xs/activity))

(s/def ::get-activity-ret
  (s/keys :opt-un [::xapi/etag
                   :get-activity-ret/activity]))

;; Agents
;; /xapi/agents

(defprotocol AgentInfoResource
  "Protocol for retrieving information on agents."
  (-get-person [this params]
    "Get an xapi person object. Throws only on invalid params."))

(s/def ::agent-info-resource-instance
  #(satisfies? AgentInfoResource %))

(s/def ::get-person-params
  (sc/with-conform-gen :xapi.agents.GET.request/params))

(s/def ::get-person-ret
  (s/keys
   :req-un [:xapi.agents.GET.response/person]
   :opt-un [::xapi/etag]))

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

(s/def ::statements-resource-instance
  #(satisfies? StatementsResource %))

(s/def ::get-statements-params
  (sc/with-conform-gen :xapi.statements.GET.request/params))

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
