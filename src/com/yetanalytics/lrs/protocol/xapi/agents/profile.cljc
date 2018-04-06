(ns com.yetanalytics.lrs.protocol.xapi.agents.profile
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]))

(defprotocol AgentProfileResource
  "Protocol for storing/retrieving agent profile documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#26-agent-profile-resource"
  (put-agent-profile [this params document content-type]
    "Store an agent profile document, overwrites.")
  (post-agent-profile [this params document content-type]
    "Store an agent profile document, merges.")
  (get-agent-profile [this params]
    "Get an agent profile document")
  (get-agent-profile-ids [this params]
    "Get multiple agent profile document ids")
  (delete-agent-profile [this params]
    "Delete an agent profile document"))

(s/def ::agent-profile-resource-instance
  #(satisfies? AgentProfileResource %))

(def put-agent-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.agents.profile.*.request.singular/params
                        :document
                        :xapi.document/generic
                        :content-type
                        string?)
           :ret nil?))

(def post-agent-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.agents.profile.*.request.singular/params
                        :document
                        :xapi.document/generic
                        :content-type
                        string?)
           :ret nil?))

(def get-agent-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.agents.profile.*.request.singular/params)
           :ret :xapi.document/generic))

(def get-agent-profile-ids-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.agents.profile.GET.request.multiple/params)
           :ret (s/coll-of :xapi.agents.profile.*.request.params/profileId
                           :kind vector? :into [])))

(def delete-agent-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.agents.profile.*.request.singular/params)
           :ret nil))
