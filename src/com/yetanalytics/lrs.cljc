(ns com.yetanalytics.lrs
  (:require [com.yetanalytics.lrs.protocol :as p]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            ))

(s/def :com.yetanalytics/lrs
  (s/and ::p/about-resource-instance
         ::p/activity-info-resource-instance
         ;; ::p/activity-profile-resource-instance
         ;; ::p/activity-state-resource-instance
         ::p/agent-info-resource-instance
         ;; ::p/agent-profile-resource-instance
         ::p/statements-resource-instance
         ))

;; About
;; /xapi/about
(defn get-about
  "Get information about this LRS"
  [lrs]
  (p/-get-about lrs))

(s/fdef get-about
        :args (s/cat :lrs ::p/about-resource-instance)
        :ret :xapi.about.GET.response/body)

;; Activities
;; /xapi/activities
(defn get-activity
  "Get the canonical representation of an activity"
  [lrs params]
  (p/-get-activity lrs params))

(s/fdef get-activity
        :args (s/cat :lrs ::p/activity-info-resource-instance
                     :params :xapi.activities.GET.request/params)
        :ret ::xs/activity)

;; TODO: /xapi/activities/profile
;; TODO: /xapi/activities/state

;; Agents
;; /xapi/agents
(defn get-person
  "Get an object representing an actor"
  [lrs params]
  (p/-get-person lrs params))

(s/fdef get-person
        :args (s/cat :lrs ::p/agent-info-resource-instance
                     :params :xapi.agents.GET.request/params)
        :ret :xapi.agents.GET.response/person)

;; TODO: /xapi/agents/profile

;; Statements
;; /xapi/statements
(defn store-statements
  "Store statements and attachments in the LRS"
  [lrs statements attachments]
  (p/-store-statements lrs statements attachments))

(s/fdef store-statements
        :args (s/cat :lrs ::p/statements-resource-instance
                     :statements ::xs/statements
                     :attachments vector?)
        :ret (s/coll-of :statement/id))

(defn get-statements
  "Get statements from the LRS"
  [lrs params ltags]
  (p/-get-statements lrs params ltags))

(s/def :xapi.statements.GET.response/attachments
  (s/coll-of map?))

(s/fdef get-statements
        :args (s/cat :lrs ::p/statements-resource-instance
                     :params :xapi.statements.GET.request/params
                     :ltags (s/coll-of ::xs/language-tag))
        :ret (s/nilable
              (s/keys
               :req-un [(or
                         :xapi.statements.GET.response/statement-result
                         ::xs/statement)
                        :xapi.statements.GET.response/attachments])))

;; TODO: auth
