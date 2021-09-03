(ns com.yetanalytics.lrs.protocol
  #?(:cljs (:require-macros [com.yetanalytics.lrs.protocol :refer [make-proto-pred
                                                                   or-error]]))
  (:require [clojure.spec.alpha :as s :include-macros true]
            [clojure.spec.gen.alpha :as sgen :include-macros true]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.lrs.xapi :as xapi]
            [com.yetanalytics.lrs.spec.common :as sc]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.document :as doc]
            [com.yetanalytics.lrs.auth :as auth]))

#?(:clj (set! *warn-on-reflection* true))

;; TODO: multiple error returns
(s/def :ret/error
  (s/with-gen
    #(instance? #?(:clj Exception
                   :cljs js/Error) %)
    (fn []
      (sgen/fmap #(apply ex-info %)
                 (sgen/tuple
                  (sgen/string-ascii)
                  (s/gen map?))))))

(s/def ::error-ret
  (s/keys :req-un [:ret/error]))

(defmacro or-error
  "Given a response spec, accept ::error-ret as a result"
  [spec]
  `(s/or :response ~spec
         :error ::error-ret))

;; About
;; /about

(defprotocol AboutResource
  "Protocol for retrieving LRS info"
  (-get-about [this auth-identity]
    "Return information about the LRS"))

(defmacro make-proto-pred
  "Build a memoized predicate for determining protocol satisfaction"
  [protocol]
  `(memoize (fn [x#]
              (satisfies? ~protocol x#))))

(def about-resource?
  (make-proto-pred AboutResource))

(s/def ::about-resource-instance
  about-resource?)

(s/def ::get-about-ret
  (or-error
   (s/keys
    :req-un [:xapi.about.GET.response/body]
    :opt-un [::xapi/etag])))

(defprotocol AboutResourceAsync
  "AsyncProtocol for retrieving LRS info."
  (-get-about-async [this auth-identity]
    "Return information about the LRS. Returns response in a promise channel."))

(def about-resource-async?
  (make-proto-pred AboutResourceAsync))

(s/def ::about-resource-async-instance
  about-resource-async?)

(s/def ::get-about-asyc-ret
  (sc/from-port
   ::get-about-ret))

;; Document APIs

(defprotocol DocumentResource
  "Protocol for storing/retrieving documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#23-state-resource"
  (-set-document [this auth-identity params document merge?]
    "Store a document, merges.")
  (-get-document [this auth-identity params]
    "Get a single document.")
  (-get-document-ids [this auth-identity params]
    "Get multiple document ids.")
  (-delete-document [this auth-identity params]
    "Delete a single document.")
  (-delete-documents [this auth-identity params]
    "Delete multiple documents."))

(def document-resource?
  (make-proto-pred DocumentResource))

(s/def ::document-resource-instance
  document-resource?)

(s/def ::set-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

(s/def ::set-document-ret
  (or-error #{{}}))

(s/def ::get-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

(s/def :get-document-ret/document
  (s/nilable :com.yetanalytics.lrs.xapi/document))

(s/def ::get-document-ret
  (or-error (s/keys :opt-un [::xapi/etag
                             :get-document-ret/document])))

(s/def ::get-document-ids-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/query-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/query-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/query-params)))

(s/def :get-document-ids-ret/document-ids
  (s/coll-of ::doc/id
             :kind vector?
             :into []))

(s/def ::get-document-ids-ret
  (or-error
   (s/keys :opt-un [::xapi/etag
                    :get-document-ids-ret/document-ids])))

(s/def ::get-document-all-params
  (s/or :single
        ::get-document-params
        :multiple
        ::get-document-ids-params))

(s/def ::delete-document-params
  (s/or :state
        (sc/with-conform-gen :xapi.document.state/id-params)
        :agent-profile
        (sc/with-conform-gen :xapi.document.agent-profile/id-params)
        :activity-profile
        (sc/with-conform-gen :xapi.document.activity-profile/id-params)))

(s/def ::delete-document-ret
  (or-error #{{}}))

(s/def ::delete-documents-params
  (s/or :state (sc/with-conform-gen :xapi.document.state/context-params)))

(s/def ::delete-documents-ret
  (or-error #{{}}))

(s/def ::delete-document-all-params
  (s/or :single
        ::get-document-params
        :multiple
        ::delete-documents-params))

(defprotocol DocumentResourceAsync
  "Async protocol for storing/retrieving documents.
   See https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#23-state-resource"
  (-set-document-async [this auth-identity params document merge?]
    "Store a document, merges. Returns a promise channel")
  (-get-document-async [this auth-identity params]
    "Get a single document. Returns a promise channel")
  (-get-document-ids-async [this auth-identity params]
    "Get multiple document ids.
     Returns a channel which will receive document ids and close.")
  (-delete-document-async [this auth-identity params]
    "Delete a single document. Returns a promise channel.")
  (-delete-documents-async [this auth-identity params]
    "Delete multiple documents. Returns a promise channel."))

(def document-resource-async?
  (make-proto-pred DocumentResourceAsync))

(s/def ::document-resource-async-instance
  document-resource-async?)

(s/def ::set-document-async-ret
  (sc/from-port
   ::set-document-ret))

(s/def ::get-document-async-ret
  (sc/from-port
   ::get-document-ret))

(s/def ::get-document-ids-async-ret
  (sc/from-port
   ::get-document-ids-ret))

(s/def ::delete-document-async-ret
  (sc/from-port
   ::delete-document-ret))

(s/def ::delete-documents-async-ret
  (sc/from-port
   ::delete-documents-ret))

;; Activities
;; /activities
(defprotocol ActivityInfoResource
  "Protocol for retrieving activity info."
  (-get-activity [this auth-identity params]
    "Get an xapi activity object."))

(def activity-info-resource?
  (make-proto-pred ActivityInfoResource))

(s/def ::activity-info-resource-instance
  activity-info-resource?)

(s/def ::get-activity-params
  (sc/with-conform-gen :xapi.activities.GET.request/params))

(s/def :get-activity-ret/activity
  (s/nilable ::xs/activity))

(s/def ::get-activity-ret
  (or-error
   (s/keys :opt-un [::xapi/etag
                    :get-activity-ret/activity])))

(defprotocol ActivityInfoResourceAsync
  "Async protocol for retrieving activity info."
  (-get-activity-async [this auth-identity params]
    "Get an xapi activity object. Returns a promise channel."))

(def activity-info-resource-async?
  (make-proto-pred ActivityInfoResourceAsync))

(s/def ::activity-info-resource-async-instance
  activity-info-resource-async?)

(s/def ::get-activity-async-ret
  (sc/from-port
   ::get-activity-ret))

;; Agents
;; /agents

(defprotocol AgentInfoResource
  "Protocol for retrieving information on agents."
  (-get-person [this auth-identity params]
    "Get an xapi person object. Throws only on invalid params."))

(def agent-info-resource?
  (make-proto-pred AgentInfoResource))

(s/def ::agent-info-resource-instance
  agent-info-resource?)

(s/def ::get-person-params
  (sc/with-conform-gen :xapi.agents.GET.request/params))

(s/def ::get-person-ret
  (or-error
   (s/keys
    :req-un [:xapi.agents.GET.response/person]
    :opt-un [::xapi/etag])))

(defprotocol AgentInfoResourceAsync
  "Async protocol for retrieving information on agents."
  (-get-person-async [this auth-identity params]
    "Get an xapi person object. Throws only on invalid params."))

(def agent-info-resource-async?
  (make-proto-pred AgentInfoResourceAsync))

(s/def ::agent-info-resource-async-instance
  agent-info-resource-async?)

(s/def ::get-person-params
  (sc/with-conform-gen :xapi.agents.GET.request/params))

(s/def ::get-person-async-ret
  (sc/from-port
   ::get-person-ret))

;; Statements
;; /statements

(defprotocol StatementsResource
  "Protocol for storing/retrieving statements, activities, agents."
  (-store-statements [this auth-identity statements attachments]
    "Store and persist validated statements and possibly attachments in the LRS,
     throwing if there's a conflict, and returning a vector of IDs.
     It is expected that the ID param will be included in statements that are PUT.")
  (-get-statements [this auth-identity params ltags]
    "Retrieve statement or statements from the LRS given params and optionally ltags.
     Returns a statement result object or a single statement.")
  (-consistent-through [this ctx auth-identity]
    "Given the ctx map, return an ISO 8601 stamp for the
     X-Experience-API-Consistent-Through header"))

(def statements-resource?
  (make-proto-pred StatementsResource))

(s/def ::statements-resource-instance
  statements-resource?)

(s/def ::get-statements-params
  (sc/with-conform-gen :xapi.statements.GET.request/params))

(s/def :store-statements-ret/statement-ids
  (s/coll-of :statement/id :kind vector? :into []))

(s/def ::store-statements-ret
  (or-error
   (s/keys :req-un [:store-statements-ret/statement-ids])))


(s/def ::get-statements-ret
  (s/or :not-found-single (s/keys :opt-un [::xapi/etag]) #_(s/map-of any? any? :count 0)
        :found-single (s/keys :opt-un [::xapi/etag
                                       ::ss/attachments]
                              :req-un [::xs/statement])
        :multiple (s/keys :opt-un [::xapi/etag]
                          :req-un [::ss/attachments
                                   :xapi.statements.GET.response/statement-result])
        :error ::error-ret))
;; TODO: wrap in map
(s/def ::consistent-through-ret
  ::xs/timestamp)

(defn throw-statement-conflict [conflicting-statement
                                extant-statement]
  (throw (ex-info "Statement Conflict"
                  {:type ::statement-conflict
                   :statement conflicting-statement
                   :extant-statement extant-statement})))

(defn throw-invalid-voiding-statement [invalid-voiding-statement]
  (throw (ex-info "Invalid Voiding Statement"
                  {:type ::invalid-voiding-statement
                   :statement invalid-voiding-statement})))

(defprotocol StatementsResourceAsync
  "Async Protocol for storing/retrieving statements, activities, agents."
  (-store-statements-async [this auth-identity statements attachments]
    "Store and persist validated statements and possibly attachments in the LRS,
     throwing if there's a conflict, and returning a channel of ids.
     It is expected that the ID param will be included in statements that are PUT.")
  (-get-statements-async [this auth-identity params ltags]
    "Retrieve statement or statements from the LRS given params and optionally ltags.
     For singular params, returns a promise channel with a single statement.
     For multiple statements GET returns a channel that will get, in order:
     Statements (if present), a more link if one is appropriate, and possibly
     n attachments for the statements.")
  (-consistent-through-async [this ctx auth-identity]
    "Given the ctx map, returns a promise channel that will get an ISO 8601
     stamp for the X-Experience-API-Consistent-Through"))

(def statements-resource-async?
  (make-proto-pred StatementsResourceAsync))

(s/def ::statements-resource-async-instance
  statements-resource-async?)

(s/def ::store-statements-async-ret
  (sc/from-port
   (or-error (s/keys :req-un [:store-statements-ret/statement-ids]))))

#_(s/def :get-statements-async-ret/statement-result-chan
  (sc/from-port-coll
   (s/cat :statements (s/* ::xs/statement)
          :more-link (s/? :xapi.statements.GET.response.statement-result/more))))

#_(s/def :get-statements-async-ret/attachments-chan
  (sc/from-port-coll
   (s/coll-of ::ss/attachment)))

(s/def ::get-statements-async-ret
  (sc/from-port-coll
   (s/alt
    ;; can return one or more errors
    :exception
    (s/cat :header #{:error}
           :error :ret/error)
    :result
    (s/cat :result
           (s/alt :s (s/cat :header #{:statement}
                            :statement (s/? ::xs/lrs-statement))
                  :ss (s/cat :statements-header #{:statements}
                             :statements (s/* ::xs/lrs-statement)
                             :more (s/? (s/cat :more-header #{:more}
                                               :more-link :xapi.statements.GET.response.statement-result/more))))
           :attachments
           (s/? (s/cat :header #{:attachments}
                       :attachments (s/* ::ss/attachment)))))))

(s/def ::consistent-through-async-ret
  (sc/from-port
   ::consistent-through-ret))

;; TODO: handle auth errors
;; Auth
(defprotocol LRSAuth
  "Protocol for an authenticatable LRS"
  (-authenticate [this ctx]
    "Given the context, return an identity or ::auth/unauthorized (401)")
  (-authorize [this ctx auth-identity]
    "Given the context and auth identity, return truthy if the user is allowed to do a given thing."))

(def lrs-auth-instance?
  (make-proto-pred LRSAuth))

(s/def ::lrs-auth-instance
  lrs-auth-instance?)

(s/def :authenticate-ret/result
  ::auth/authenticate-result)

(s/def ::authenticate-ret
  (or-error (s/keys :req-un [:authenticate-ret/result])))

(s/def :authorize-ret/result
  boolean?)

(s/def ::authorize-ret
  (or-error (s/keys :req-un [:authorize-ret/result])))

(defprotocol LRSAuthAsync
  "Protocol for an authenticatable LRS"
  (-authenticate-async [this ctx]
    "Given the context, return an identity or ::auth/unauthorized (401) on a promise channel")
  (-authorize-async [this ctx auth-identity]
    "Given the context and auth-identity, return true if the user is allowed to do a given thing, on a promise-channel"))

(def lrs-auth-async-instance?
  (make-proto-pred LRSAuthAsync))

(s/def ::lrs-auth-async-instance
  lrs-auth-async-instance?)

(s/def ::authenticate-async-ret
  (sc/from-port
   ::authenticate-ret))

(s/def ::authorize-async-ret
  (sc/from-port
   ::authorize-ret))


;; Spec for the whole LRS
(s/def ::lrs ;; A minimum viable LRS
  (s/and ::about-resource-instance
         ::statements-resource-instance
         ::activity-info-resource-instance
         ::agent-info-resource-instance
         ::document-resource-instance
         ::lrs-auth-instance
         ))

;; The same, all async
(s/def ::lrs-async ;; A minimum viable LRS
  (s/and ::about-resource-async-instance
         ::statements-resource-async-instance
         ::activity-info-resource-async-instance
         ::agent-info-resource-async-instance
         ::document-resource-async-instance
         ::lrs-auth-async-instance
         ))
