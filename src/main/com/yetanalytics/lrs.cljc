(ns com.yetanalytics.lrs
  (:require [com.yetanalytics.lrs.protocol :as p]
            [clojure.spec.alpha :as s :include-macros true]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.document :as doc]
            [com.yetanalytics.lrs.auth :as auth]))

(defn lrs-gen-fn
  "Generate a full LRS for specs"
  []
  (s/gen :com.yetanalytics.lrs.impl.memory/lrs))

;; About
;; /xapi/about
(defn get-about
  "Get information about this LRS"
  [lrs auth-identity]
  (p/-get-about lrs auth-identity))

(s/fdef get-about
        :args (s/cat :lrs (s/with-gen ::p/about-resource-instance
                            lrs-gen-fn)
                     :auth-identity (s/nilable ::auth/identity))
        :ret ::p/get-about-ret)

(defn get-about-async
  "Get information about this LRS. Returns a promise channel."
  [lrs auth-identity]
  (p/-get-about-async lrs auth-identity))

(s/fdef get-about-async
        :args (s/cat :lrs (s/with-gen ::p/about-resource-async-instance
                            lrs-gen-fn)
                     :auth-identity (s/nilable ::auth/identity))
        :ret ::p/get-about-asyc-ret)

;; Documents
(defn set-document
  [lrs auth-identity params document merge?]
  (p/-set-document lrs auth-identity params document merge?))

(s/fdef
 set-document
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-instance
         lrs-gen-fn)
  :auth-identity ::auth/identity
  :params ::p/set-document-params
  :document :com.yetanalytics.lrs.xapi/document
  :merge? (s/nilable boolean?))
 :ret ::p/set-document-ret)

(defn set-document-async
  [lrs auth-identity params document merge?]
  (p/-set-document-async lrs auth-identity params document merge?))

(s/fdef
 set-document-async
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-async-instance
         lrs-gen-fn)
  :auth-identity ::auth/identity
  :params ::p/set-document-params
  :document :com.yetanalytics.lrs.xapi/document
  :merge? (s/nilable boolean?))
 :ret ::p/set-document-async-ret)

(defn get-document
  [lrs auth-identity params]
  (p/-get-document lrs auth-identity params))

(s/fdef
 get-document
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-instance
         lrs-gen-fn)
  :auth-identity ::auth/identity
  :params ::p/get-document-params)
 :ret ::p/get-document-ret)

(defn get-document-async
  [lrs auth-identity params]
  (p/-get-document-async lrs auth-identity params))

(s/fdef
 get-document-async
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-async-instance
         lrs-gen-fn)
  :auth-identity ::auth/identity
  :params ::p/get-document-params)
 :ret ::p/get-document-async-ret)

(defn get-document-ids
  [lrs auth-identity params]
  (p/-get-document-ids lrs auth-identity params))

(s/fdef
 get-document-ids
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-instance
         lrs-gen-fn)
  :auth-identity ::auth/identity
  :params ::p/get-document-ids-params)
 :ret ::p/get-document-ids-ret)

(defn get-document-ids-async
  [lrs auth-identity params]
  (p/-get-document-ids-async lrs auth-identity params))

(s/fdef
 get-document-ids-async
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-async-instance
         lrs-gen-fn)
  :auth-identity ::auth/identity
  :params ::p/get-document-ids-params)
 :ret ::p/get-document-ids-async-ret)

(defn delete-document
  [lrs auth-identity params]
  (p/-delete-document lrs auth-identity params))

(s/fdef
 delete-document
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-instance
               lrs-gen-fn)
        :auth-identity ::auth/identity
        :params ::p/delete-document-params)
 :ret ::p/delete-document-ret)

(defn delete-document-async
  [lrs auth-identity params]
  (p/-delete-document-async lrs auth-identity params))

(s/fdef
 delete-document-async
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-async-instance
               lrs-gen-fn)
        :auth-identity ::auth/identity
        :params ::p/delete-document-params)
 :ret ::p/delete-document-async-ret)

(defn delete-documents
  [lrs auth-identity params]
  (p/-delete-documents lrs auth-identity params))

(s/fdef
 delete-documents
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-instance
               lrs-gen-fn)
        :auth-identity ::auth/identity
        :params ::p/delete-documents-params)
 :ret ::p/delete-documents-ret)

(defn delete-documents-async
  [lrs auth-identity params]
  (p/-delete-documents-async lrs auth-identity params))

(s/fdef
 delete-documents-async
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-async-instance
               lrs-gen-fn)
        :auth-identity ::auth/identity
        :params ::p/delete-documents-params)
 :ret ::p/delete-documents-async-ret)


;; Activities
;; /xapi/activities
(defn get-activity
  "Get the canonical representation of an activity"
  [lrs auth-identity params]
  (p/-get-activity lrs auth-identity params))

(s/fdef get-activity
        :args (s/cat :lrs (s/with-gen ::p/activity-info-resource-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :params :xapi.activities.GET.request/params)
        :ret ::p/get-activity-ret)

(defn get-activity-async
  "Get the canonical representation of an activity"
  [lrs auth-identity params]
  (p/-get-activity-async lrs auth-identity params))

(s/fdef get-activity-async
        :args (s/cat :lrs (s/with-gen ::p/activity-info-resource-async-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :params :xapi.activities.GET.request/params)
        :ret ::p/get-activity-async-ret)


;; Agents
;; /xapi/agents
(defn get-person
  "Get an object representing an actor"
  [lrs auth-identity params]
  (p/-get-person lrs auth-identity params))

(s/fdef get-person
        :args (s/cat :lrs (s/with-gen ::p/agent-info-resource-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :params ::p/get-person-params)
        :ret ::p/get-person-ret)

(defn get-person-async
  "Get an object representing an actor"
  [lrs auth-identity params]
  (p/-get-person-async lrs auth-identity params))

(s/fdef get-person-async
        :args (s/cat :lrs (s/with-gen ::p/agent-info-resource-async-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :params ::p/get-person-params)
        :ret ::p/get-person-async-ret)


;; TODO: /xapi/agents/profile

;; Statements
;; /xapi/statements
(defn store-statements
  "Store statements and attachments in the LRS"
  [lrs auth-identity statements attachments]
  (p/-store-statements lrs auth-identity statements attachments))

(s/fdef store-statements
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :statements ::xs/statements
                     :attachments ::ss/attachments)
        :ret ::p/store-statements-ret)

(defn store-statements-async
  "Store statements and attachments in the LRS"
  [lrs auth-identity statements attachments]
  (p/-store-statements-async lrs auth-identity statements attachments))

(s/fdef store-statements-async
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :statements ::xs/statements
                     :attachments ::ss/attachments)
        :ret ::p/store-statements-async-ret)


(defn get-statements
  "Get statements from the LRS"
  [lrs auth-identity params ltags]
  (p/-get-statements lrs auth-identity params ltags))

(s/fdef get-statements
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :params ::p/get-statements-params
                     :ltags (s/coll-of ::xs/language-tag))
        :ret ::p/get-statements-ret)

(defn get-statements-async
  "Get statements from the LRS"
  [lrs auth-identity params ltags]
  (p/-get-statements-async lrs auth-identity params ltags))

(s/fdef get-statements-async
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                            lrs-gen-fn)
                     :auth-identity ::auth/identity
                     :params ::p/get-statements-params
                     :ltags (s/coll-of ::xs/language-tag))
        :ret ::p/get-statements-async-ret)


(defn consistent-through
  "Get a timestamp for use in the X-Experience-API-Consistent-Through header"
  [lrs ctx auth-identity]
  (p/-consistent-through lrs ctx auth-identity))

(s/fdef consistent-through
  :args (s/cat :lrs (s/with-gen ::p/statements-resource-instance
                      lrs-gen-fn)
               :ctx map?
               :auth-identity ::auth/identity)
  :ret ::p/consistent-through-ret)

(defn consistent-through-async
  "Get a timestamp for use in the X-Experience-API-Consistent-Through header"
  [lrs ctx auth-identity]
  (p/-consistent-through-async lrs ctx auth-identity))

(s/fdef consistent-through-async
  :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                      lrs-gen-fn)
               :ctx map?
               :auth-identity ::auth/identity)
  :ret ::p/consistent-through-async-ret)

;; Auth

(defn authenticate
  "Given the LRS and context, return an identity or nil (401)"
  [lrs ctx]
  (p/-authenticate lrs ctx))

(s/fdef authenticate
  :args (s/cat :lrs (s/with-gen ::p/lrs-auth-instance
                      lrs-gen-fn)
               :ctx map?)
  :ret ::p/authenticate-ret)

(defn authorize
  "Given the LRS and context, return true if the user is allowed to do a given thing."
  [lrs ctx auth-identity]
  (p/-authorize lrs ctx auth-identity))

(s/fdef auth
  :args (s/cat :lrs (s/with-gen ::p/lrs-auth-instance
                      lrs-gen-fn)
               :ctx map?
               :auth-identity ::auth/identity)
  :ret ::p/authorize-ret)

(defn authenticate-async
  "Given the LRS and context, return an identity or nil (401), on a promise channel"
  [lrs ctx]
  (p/-authenticate-async lrs ctx))

(s/fdef authenticate-async
  :args (s/cat :lrs (s/with-gen ::p/lrs-auth-async-instance
                      lrs-gen-fn)
               :ctx map?)
  :ret ::p/authenticate-async-ret)

(defn authorize-async
  "Given the LRS and context, return true if the user is allowed to do a given thing, on a promise-channel"
  [lrs ctx auth-identity]
  (p/-authorize-async lrs ctx auth-identity))

(s/fdef authorize-async
  :args (s/cat :lrs (s/with-gen ::p/lrs-auth-async-instance
                      lrs-gen-fn)
               :ctx map?
               :auth-identity ::auth/identity)
  :ret ::p/authorize-async-ret)
