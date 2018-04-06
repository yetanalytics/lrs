(ns com.yetanalytics.lrs.protocol.xapi.statements
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [xapi-schema.spec :as xs]))

(defprotocol StatementsResource
  "Protocol for storing/retrieving statements, activities, agents."
  (store-statements [this statements] [this statements attachments]
    "Store and persist validated statements and possibly attachments in the LRS,
     throwing if there's a conflict, and returning a vector of IDs.
     It is expected that the ID param will be included in statements that are PUT.")
  (get-statements [this params] [this params ltags]
    "Retrieve statement or statements from the LRS given params and optionally ltags.
     Returns a statement result object or a single statement."))

(s/def ::statements-resource-instance
  #(satisfies? StatementsResource %))

(s/def ::attachments
  vector?) ;; TODO: make a spec for these

(def store-statements-partial-spec
  (s/fspec :args (s/cat :statements
                        ::xs/statements
                        :attachments
                        (s/? ::attachments))
           :ret (s/coll-of :statement/id :kind vector? :into [])))

(def get-statements-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.statements.GET.request/params
                        :ltags
                        (s/? (s/coll-of ::xs/language-tag :kind vector? :into [])))
           :ret
           (s/or :statement-result
                 :xapi.statements.GET.response/statement-result
                 :single-statement
                 ::xs/statements)))
