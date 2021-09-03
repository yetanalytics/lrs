(ns com.yetanalytics.lrs.bench.post
  "Specs for payload post"
  (:require [clojure.spec.alpha :as s]
            [java-time :as t]))

(s/def ::responses
  (s/every map? :min-count 1))

(s/def ::ids
  (s/every :statement/id :min-count 1))

(s/def ::t-zero
  t/instant?)

(s/def ::t-end
  t/instant?)

(def spec
  (s/keys :req-un [::responses
                   ::ids
                   ::t-zero
                   ::t-end]))
