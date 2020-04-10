(ns com.yetanalytics.lrs.bench.get
  "Specs for payload retrieval"
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]))

(s/def ::statements
  ::xs/lrs-statements)

(def spec
  (s/keys :req-un [::statements]))
