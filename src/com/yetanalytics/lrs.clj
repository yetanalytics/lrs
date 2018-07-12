(ns com.yetanalytics.lrs
  (:require [com.yetanalytics.lrs.protocol :as p]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.document :as doc]
            ))

(defn lrs-gen-fn
  "Generate a full LRS for specs"
  []
  (s/gen :com.yetanalytics.lrs.impl.memory/lrs))

;; About
;; /xapi/about
(defn get-about
  "Get information about this LRS"
  [lrs]
  (p/-get-about lrs))

(s/fdef get-about
        :args (s/cat :lrs (s/with-gen ::p/about-resource-instance
                            lrs-gen-fn))
        :ret ::p/get-about-ret)

(defn get-about-async
  "Get information about this LRS. Returns a promise channel."
  [lrs]
  (p/-get-about-async lrs))

(s/fdef get-about-async
        :args (s/cat :lrs (s/with-gen ::p/about-resource-async-instance
                            lrs-gen-fn))
        :ret ::p/get-about-asyc-ret)

;; Documents
(defn set-document
  [lrs params document merge?]
  (p/-set-document lrs params document merge?))

(s/fdef
 set-document
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-instance
         lrs-gen-fn)
  :params ::p/set-document-params
  :document :com.yetanalytics.lrs.xapi/document
  :merge? (s/nilable boolean?))
 :ret ::p/set-document-ret)

(defn set-document-async
  [lrs params document merge?]
  (p/-set-document-async lrs params document merge?))

(s/fdef
 set-document-async
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-async-instance
         lrs-gen-fn)
  :params ::p/set-document-params
  :document :com.yetanalytics.lrs.xapi/document
  :merge? (s/nilable boolean?))
 :ret ::p/set-document-async-ret)

(defn get-document
  [lrs params]
  (p/-get-document lrs params))

(s/fdef
 get-document
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-instance
         lrs-gen-fn)
  :params ::p/get-document-params)
 :ret ::p/get-document-ret)

(defn get-document-async
  [lrs params]
  (p/-get-document-async lrs params))

(s/fdef
 get-document-async
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-async-instance
         lrs-gen-fn)
  :params ::p/get-document-params)
 :ret ::p/get-document-async-ret)

(defn get-document-ids
  [lrs params]
  (p/-get-document-ids lrs params))

(s/fdef
 get-document-ids
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-instance
         lrs-gen-fn)
  :params ::p/get-document-ids-params)
 :ret ::p/get-document-ids-ret)

(defn get-document-ids-async
  [lrs params]
  (p/-get-document-ids-async lrs params))

(s/fdef
 get-document-ids-async
 :args
 (s/cat
  :lrs (s/with-gen ::p/document-resource-async-instance
         lrs-gen-fn)
  :params ::p/get-document-ids-params)
 :ret ::p/get-document-ids-async-ret)

(defn delete-document
  [lrs params]
  (p/-delete-document lrs params))

(s/fdef
 delete-document
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-instance
               lrs-gen-fn)
        :params ::p/delete-document-params)
 :ret ::p/delete-document-ret)

(defn delete-document-async
  [lrs params]
  (p/-delete-document-async lrs params))

(s/fdef
 delete-document-async
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-async-instance
               lrs-gen-fn)
        :params ::p/delete-document-params)
 :ret ::p/delete-document-async-ret)

(defn delete-documents
  [lrs params]
  (p/-delete-documents lrs params))

(s/fdef
 delete-documents
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-instance
               lrs-gen-fn)
        :params ::p/delete-documents-params)
 :ret ::p/delete-documents-ret)

(defn delete-documents-async
  [lrs params]
  (p/-delete-documents-async lrs params))

(s/fdef
 delete-documents-async
 :args (s/cat
        :lrs (s/with-gen ::p/document-resource-async-instance
               lrs-gen-fn)
        :params ::p/delete-documents-params)
 :ret ::p/delete-documents-async-ret)


;; Activities
;; /xapi/activities
(defn get-activity
  "Get the canonical representation of an activity"
  [lrs params]
  (p/-get-activity lrs params))

(s/fdef get-activity
        :args (s/cat :lrs (s/with-gen ::p/activity-info-resource-instance
                            lrs-gen-fn)
                     :params :xapi.activities.GET.request/params)
        :ret ::p/get-activity-ret)

(defn get-activity-async
  "Get the canonical representation of an activity"
  [lrs params]
  (p/-get-activity-async lrs params))

(s/fdef get-activity-async
        :args (s/cat :lrs (s/with-gen ::p/activity-info-resource-async-instance
                            lrs-gen-fn)
                     :params :xapi.activities.GET.request/params)
        :ret ::p/get-activity-async-ret)


;; Agents
;; /xapi/agents
(defn get-person
  "Get an object representing an actor"
  [lrs params]
  (p/-get-person lrs params))

(s/fdef get-person
        :args (s/cat :lrs (s/with-gen ::p/agent-info-resource-instance
                            lrs-gen-fn)
                     :params ::p/get-person-params)
        :ret ::p/get-person-ret)

(defn get-person-async
  "Get an object representing an actor"
  [lrs params]
  (p/-get-person-async lrs params))

(s/fdef get-person-async
        :args (s/cat :lrs (s/with-gen ::p/agent-info-resource-async-instance
                            lrs-gen-fn)
                     :params ::p/get-person-params)
        :ret ::p/get-person-async-ret)


;; TODO: /xapi/agents/profile

;; Statements
;; /xapi/statements
(defn store-statements
  "Store statements and attachments in the LRS"
  [lrs statements attachments]
  (p/-store-statements lrs statements attachments))

(s/fdef store-statements
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-instance
                            lrs-gen-fn)
                     :statements ::xs/statements
                     :attachments ::ss/attachments)
        :ret ::p/store-statements-ret)

(defn store-statements-async
  "Store statements and attachments in the LRS"
  [lrs statements attachments]
  (p/-store-statements-async lrs statements attachments))

(s/fdef store-statements-async
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                            lrs-gen-fn)
                     :statements ::xs/statements
                     :attachments ::ss/attachments)
        :ret ::p/store-statements-async-ret)


(defn get-statements
  "Get statements from the LRS"
  [lrs params ltags]
  (p/-get-statements lrs params ltags))

(s/fdef get-statements
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-instance
                            lrs-gen-fn)
                     :params ::p/get-statements-params
                     :ltags (s/coll-of ::xs/language-tag))
        :ret ::p/get-statements-ret)

(defn get-statements-async
  "Get statements from the LRS"
  [lrs params ltags]
  (p/-get-statements-async lrs params ltags))

(s/fdef get-statements-async
        :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                            lrs-gen-fn)
                     :params ::p/get-statements-params
                     :ltags (s/coll-of ::xs/language-tag))
        :ret ::p/get-statements-async-ret)


(defn consistent-through
  "Get a timestamp for use in the X-Experience-API-Consistent-Through header"
  [lrs ctx]
  (p/-consistent-through lrs ctx))

(s/fdef consistent-through
  :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                      lrs-gen-fn)
               :ctx map?)
  :ret ::p/consistent-through-ret)

(defn consistent-through-async
  "Get a timestamp for use in the X-Experience-API-Consistent-Through header"
  [lrs ctx]
  (p/-consistent-through-async lrs ctx))

(s/fdef consistent-through-async
  :args (s/cat :lrs (s/with-gen ::p/statements-resource-async-instance
                      lrs-gen-fn)
               :ctx map?)
  :ret ::p/consistent-through-async-ret)


;; TODO: auth
