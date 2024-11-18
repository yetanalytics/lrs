(ns com.yetanalytics.lrs.xapi.activities
  (:require [clojure.spec.alpha :as s :include-macros true]
            [xapi-schema.spec :as xs]))

(s/fdef merge-activity
  :args (s/cat :a-1 (s/alt :nil nil?
                           :activity ::xs/activity)
               :a-2 ::xs/activity)
  :ret ::xs/activity)

(def interaction-keys
  ["interactionType"
   "correctResponsesPattern"
   "choices"
   "scale"
   "source"
   "target"
   "steps"])

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
      (assoc
       "definition"
       (let [?def-2-interaction (select-keys ?def-2 interaction-keys)]
         (merge-with
          ;; merge maps, overwrite all else
          (fn [val-1 val-2]
            (if (map? val-1)
              (merge val-1 val-2)
              val-2))
          ;; clear def-1 interaction fields if def-2 has one
          (if (not-empty ?def-2-interaction)
            (apply dissoc ?def-1 interaction-keys)
            ?def-1)
          ?def-2))))
    ;; no merge
    a-2))
