(ns com.yetanalytics.lrs.protocol.xapi.activities.profile
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]))

(defprotocol ActivityProfileResource
  "Protocol for storing/retrieving activity profile documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#27-activity-profile-resource"
  (put-activity-profile [this params document content-type]
    "Store an activity profile document, overwrites.")
  (post-activity-profile [this params document content-type]
    "Store an activity profile document, merges.")
  (get-activity-profile [this params]
    "Get an activity profile document")
  (get-activity-profile-ids [this params]
    "Get multiple activity profile document ids")
  (delete-activity-profile [this params]
    "Delete an activity profile document"))

(s/def ::activity-profile-resource-instance
  #(satisfies? ActivityProfileResource %))

(def put-activity-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.profile.*.request.singular/params
                        :document
                        :xapi.document/generic
                        :content-type
                        string?)
           :ret nil?))

(def post-activity-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.profile.*.request.singular/params
                        :document
                        :xapi.document/generic
                        :content-type
                        string?)
           :ret nil?))

(def get-activity-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.profile.*.request.singular/params)
           :ret :xapi.document/generic))

(def get-activity-profile-ids-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.profile.GET.request.multiple/params)
           :ret (s/coll-of :xapi.activities.profile.*.request.params/profileId
                           :kind vector? :into [])))

(def delete-activity-profile-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.profile.*.request.singular/params)
           :ret nil))
