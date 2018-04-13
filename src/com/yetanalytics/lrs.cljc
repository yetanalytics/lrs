(ns com.yetanalytics.lrs
  (:require [com.yetanalytics.lrs.protocol :as p]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            ))

;; About
;; /xapi/about
(defn get-about
  "Get information about this LRS"
  [lrs]
  (p/-get-about lrs))

;; Activities
;; /xapi/activities
(defn get-activity
  "Get the canonical representation of an activity"
  [lrs params]
  (p/-get-activity lrs params))

;; TODO: /xapi/activities/profile
;; TODO: /xapi/activities/state

;; Agents
;; /xapi/agents
(defn get-person
  "Get an object representing an actor"
  [lrs params]
  (p/-get-person lrs params))

;; TODO: /xapi/agents/profile

;; Statements
;; /xapi/statements
(defn store-statements
  "Store statements and attachments in the LRS"
  [lrs statements attachments]
  (p/-store-statements lrs statements attachments))

(defn get-statements
  "Get statements from the LRS"
  [lrs params ltags]
  (p/-get-statements lrs params ltags))

;; TODO: auth
