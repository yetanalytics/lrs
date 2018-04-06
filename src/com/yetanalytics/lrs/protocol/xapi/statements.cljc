(ns com.yetanalytics.lrs.protocol.xapi.statements
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [xapi-schema.spec :as xs]))

(defprotocol StatementsResource
  "Protocol for storing/retrieving statements, activities, agents."
  (store-statements [this statements attachments]
    "Store and persist validated statements and possibly attachments in the LRS,
     throwing if there's a conflict, and returning a vector of IDs.
     It is expected that the ID param will be included in statements that are PUT.")
  (get-statements [this params ltags]
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
                        ::attachments)
           :ret (s/nilable (s/coll-of :statement/id :kind vector? :into []))))

(def get-statements-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.statements.GET.request/params
                        :ltags
                        (s/coll-of ::xs/language-tag :kind vector? :into []))
           :ret
           (s/or :statement-result
                 :xapi.statements.GET.response/statement-result
                 :single-statement
                 ::xs/statements
                 :nil
                 nil?)))

(defn throw-invalid-params [params params-spec]
  (throw (ex-info "Invalid Pa rams"
                  {:type ::invalid-params
                   :params params
                   :schema-error (s/explain-data params-spec
                                                 params)})))

(defn throw-invalid-statements [invalid-statements]
  (throw (ex-info "Invalid Statements"
                  {:type ::invalid-statements
                   :invalid-statements invalid-statements
                   :schema-error (s/explain-data ::xs/statements
                                                 invalid-statements)})))

(defn throw-invalid-attachments []
  (throw (ex-info "Invalid Attachments"
                  {:type ::invalid-attachments})))

(defn throw-conflicting-statements [conflicting-statements
                                    extant-statements]
  (throw (ex-info "Conflicting Statements"
                  {:type ::conflicting-statements
                   :conflicting-statements conflicting-statements
                   :extant-statements extant-statements})))

(defn throw-invalid-voiding-statements [invalid-voiding-statements]
  (throw (ex-info "Invalid Voiding Statements"
                  {:type ::invalid-voiding-statements
                   :invalid-voiding-statements invalid-voiding-statements})))
