(ns com.yetanalytics.lrs.xapi.activities
  (:require [clojure.spec.alpha :as s :include-macros true]
            [xapi-schema.spec :as xs]))

(defn merge-activity
  [{?id-1  "id"
    ?def-1 "definition"
    :as    ?a-1}
   {?def-2 "definition"
    :as    a-2}]
  (if ?a-1
    ;; merge!
    (cond-> {"id" ?id-1 "objectType" "Activity"}
      (or ?def-1 ?def-2)
      (assoc "definition"
             (merge-with
              merge
              ?def-1
              (select-keys ?def-2
                           ["name"
                            "description"]))))
    ;; no merge
    a-2))

(s/fdef merge-activity
        :args (s/cat :a-1 (s/alt :nil nil?
                                 :activity ::xs/activity)
                     :a-2 ::xs/activity)
        :ret ::xs/activity)
