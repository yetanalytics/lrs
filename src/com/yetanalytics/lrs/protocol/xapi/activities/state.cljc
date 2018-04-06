(ns com.yetanalytics.lrs.protocol.xapi.activities.state
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]))

(defprotocol ActivityStateResource
  "Protocol for storing/retrieving activity state documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#23-state-resource"
  (put-state [this params document content-type]
    "Store a state document, overwrites.")
  (post-state [this params document content-type]
    "Store a state document, merges.")
  (get-state [this params]
    "Get a single state document.")
  (get-state-ids [this params]
    "Get multiple state document ids.")
  (delete-state [this params]
    "Delete a single state document.")
  (delete-states [this params]
    "Delete multiple state documents."))

(s/def ::activity-state-resource-instance
  #(satisfies? ActivityStateResource %))

(def put-state-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.state.*.request.singular/params
                        :document
                        :xapi.document/generic
                        :content-type
                        string?)
           :ret nil?))

(def post-state-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.state.*.request.singular/params
                        :document
                        :xapi.document/generic
                        :content-type
                        string?)
           :ret nil?))

(def get-state-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.state.*.request.singular/params)
           :ret :xapi.document/generic))

(def get-state-ids-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.state.GET.request.multiple/params)
           :ret (s/coll-of :xapi.activities.state.*.request.params/stateId
                           :kind vector?
                           :into [])))

(def delete-state-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.state.*.request.singular/params)
           :ret nil?))

(def delete-states-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.state.DELETE.request.multiple/params)
           :ret (s/coll-of :xapi.activities.state.*.request.params/stateId
                           :kind vector?
                           :into [])))
