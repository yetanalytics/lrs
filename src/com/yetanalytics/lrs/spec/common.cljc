(ns com.yetanalytics.lrs.spec.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]))

(def string-ascii-not-empty
  (s/with-gen (s/and string?
                     not-empty)
    (fn []
      (sgen/not-empty
       (sgen/string-ascii)))))

(def string-alphanumeric-not-empty
  (s/with-gen (s/and string?
                     not-empty)
    (fn []
      (sgen/not-empty
       (sgen/string-alphanumeric)))))
