(ns com.yetanalytics.lrs.bench.options
  "Specs for user options"
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs])
  (:import [java.net.http HttpClient]))

(s/def ::run-id
  string?)

(s/def ::lrs-endpoint
  string?)

(s/def ::payload
  ::xs/statements)

(s/def ::payload-input-path
  string?)

(s/def ::size
  pos-int?)

(s/def ::batch-size
  pos-int?)

(s/def ::send-ids?
  boolean?)

(s/def ::dry-run?
  boolean?)

(s/def ::request-options
  map?)

(s/def ::http-client
  #(instance? HttpClient %))

;; options passed in
(def spec
  (s/keys :req-un [::lrs-endpoint
                   (or ::payload
                       ::payload-input-path)]
          :opt-un [::run-id
                   ::size
                   ::batch-size
                   ::send-ids?
                   ::dry-run?
                   ::request-options
                   ::http-client]))
