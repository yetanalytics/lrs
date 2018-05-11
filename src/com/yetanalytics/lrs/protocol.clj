(ns com.yetanalytics.lrs.protocol
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [xapi-schema.spec :as xs]))
(set! *warn-on-reflection* true)


;; About
;; /xapi/about

(defprotocol AboutResource
  "Protocol for retrieving LRS info"
  (-get-about [this]
    "Return information about the LRS"))

(s/def ::about-resource-instance
  #(satisfies? AboutResource %))

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
;; TODO: all the ones called from routes should be non-conforming
;; unless desired/needed
(s/def ::document-resource-instance
  #(satisfies? DocumentResource %))

(s/def ::set-document-params
  (s/or :state
        :xapi.document.state/id-params
        :agent-profile
        :xapi.document.agent-profile/id-params
        :activity-profile
        :xapi.document.activity-profile/id-params))

(s/def ::get-document-params
  (s/or :state
        :xapi.document.state/id-params
        :agent-profile
        :xapi.document.agent-profile/id-params
        :activity-profile
        :xapi.document.activity-profile/id-params))

(s/def ::get-document-ids-params
  (s/or :state
        :xapi.document.state/query-params
        :agent-profile
        :xapi.document.agent-profile/query-params
        :activity-profile
        :xapi.document.activity-profile/query-params))

(s/def ::get-document-all-params
  (s/or :single
        ::get-document-params
        :multiple
        ::get-document-ids-params))

(s/def ::delete-document-params
  (s/or :state
        :xapi.document.state/id-params
        :agent-profile
        :xapi.document.agent-profile/id-params
        :activity-profile
        :xapi.document.activity-profile/id-params))

(s/def ::delete-documents-params
  (s/or :state :xapi.document.state/context-params))

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

;; /xapi/activities/profile

(defprotocol ActivityProfileResource
  "Protocol for storing/retrieving activity profile documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#27-activity-profile-resource"
  (-set-activity-profile [this params document]
    "Store an activity profile document, overwrites.")
  (-update-activity-profile [this params document]
    "Store an activity profile document, merges.")
  (-get-activity-profile [this params]
    "Get an activity profile document")
  (-get-activity-profile-ids [this params]
    "Get multiple activity profile document ids")
  (-delete-activity-profile [this params]
    "Delete an activity profile document"))

(s/def ::activity-profile-resource-instance
  #(satisfies? ActivityProfileResource %))

;; /xapi/activities/state

(defprotocol ActivityStateResource
  "Protocol for storing/retrieving activity state documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#23-state-resource"
  (-set-state [this params document]
    "Store a state document, overwrites.")
  (-update-state [this params document]
    "Store a state document, merges.")
  (-get-state [this params]
    "Get a single state document.")
  (-get-state-ids [this params]
    "Get multiple state document ids.")
  (-delete-state [this params]
    "Delete a single state document.")
  (-delete-states [this params]
    "Delete multiple state documents."))

(s/def ::activity-state-resource-instance
  #(satisfies? ActivityStateResource %))

;; Agents
;; /xapi/agents

(defprotocol AgentInfoResource
  "Protocol for retrieving information on agents."
  (-get-person [this params]
    "Get an xapi person object. Throws only on invalid params."))

(s/def ::agent-info-resource-instance
  #(satisfies? AgentInfoResource %))

;; /xapi/agents/profile

(defprotocol AgentProfileResource
  "Protocol for storing/retrieving agent profile documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#26-agent-profile-resource"
  (-set-agent-profile [this params document]
    "Store an agent profile document, overwrites.")
  (-update-agent-profile [this params document]
    "Store an agent profile document, merges.")
  (-get-agent-profile [this params]
    "Get an agent profile document")
  (-get-agent-profile-ids [this params]
    "Get multiple agent profile document ids")
  (-delete-agent-profile [this params]
    "Delete an agent profile document"))

(s/def ::agent-profile-resource-instance
  #(satisfies? AgentProfileResource %))

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
         ::activity-info-resource-instance
         ::activity-profile-resource-instance
         ::activity-state-resource-instance
         ::agent-info-resource-instance
         ::agent-profile-resource-instance
         ::statements-resource-instance
         ))
