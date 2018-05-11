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

(defn with-conform-gen
  "Return a version of the spec that generates then conforms"
  [spec]
  (s/with-gen spec
    (fn []
      (sgen/fmap (partial s/conform spec)
                 (s/gen spec)))))
