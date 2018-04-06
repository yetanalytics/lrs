(ns com.yetanalytics.lrs.protocol.xapi.agents
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]))

(defprotocol AgentInfoResource
  "Protocol for retrieving information on agents."
  (get-person [this params]
    "Get an xapi person object."))

(s/def ::agent-info-resource-instance
  #(satisfies? AgentInfoResource %))

(def get-person-partial-spec
  "A partial specification for the AgentInfoResource/get-profile"
  (s/fspec :args (s/cat :params :xapi.agents.GET.request/params)
           :ret :xapi.agents.GET.response/person))
