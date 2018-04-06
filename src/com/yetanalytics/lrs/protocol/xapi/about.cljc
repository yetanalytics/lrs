(ns com.yetanalytics.lrs.protocol.xapi.about
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]))

(defprotocol AboutResource
  "Protocol for retrieving LRS info"
  (get-about [this]
    "Return information about the LRS"))

(s/def ::about-resource-instance
  #(satisfies? AboutResource %))

(def get-about-partial-spec
  (s/fspec :args (s/cat)
           :ret :xapi.about.GET.response/body))
