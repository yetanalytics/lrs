(ns com.yetanalytics.lrs.protocol.xapi.activities
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [xapi-schema.spec :as xs]))

(defprotocol ActivityInfoResource
  "Protocol for retrieving activity info."
  (get-activity [this params]
    "Get an xapi activity object."))

(s/def ::activity-info-resource-instance
  #(satisfies? ActivityInfoResource %))

(def get-activity-partial-spec
  (s/fspec :args (s/cat :params
                        :xapi.activities.GET.request/params)
           :ret (s/nilable ::xs/activity)))

;; Errors
(defn throw-invalid-params [params]
  (throw (ex-info "Invalid Activity Params"
                  {:type ::invalid-params
                   :params params
                   :schema-error (s/explain-data
                                  :xapi.activities.GET.request/params
                                  params)})))
