(ns com.yetanalytics.lrs.protocol.xapi
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.lrs.protocol.xapi.about :as about]
            [com.yetanalytics.lrs.protocol.xapi.activities :as activities]
            [com.yetanalytics.lrs.protocol.xapi.activities.profile :as activities-profile]
            [com.yetanalytics.lrs.protocol.xapi.activities.state :as activities-state]
            [com.yetanalytics.lrs.protocol.xapi.agents :as agents]
            [com.yetanalytics.lrs.protocol.xapi.agents.profile :as agents-profile]
            [com.yetanalytics.lrs.protocol.xapi.statements :as statements]
            [com.yetanalytics.lrs.protocol.auth :as auth]))

(s/def ::lrs ;; A minimum viable LRS
  (s/and ::about/about-resource-instance
         ::activities/activity-info-resource-instance
         ::activities-profile/activity-profile-resource-instance
         ::activities-state/activity-state-resource-instance
         ::agents/agent-info-resource-instance
         ::agents-profile/agent-profile-resource-instance
         ::statements/statements-resource-instance
         ::auth/lrs-auth-instance))
