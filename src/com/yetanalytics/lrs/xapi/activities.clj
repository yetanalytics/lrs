(ns com.yetanalytics.lrs.xapi.activities
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [clojure.data.priority-map :as pm]
            [clojure.data.json :as json]
            [clojure.walk :as w]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [ring.util.codec :as codec]))

(defn merge-activity
  [{?id-1 "id"
    ?def-1 "definition"
    :as ?a-1}
   {id-2 "id"
    ?def-2 "definition"
    :as a-2}]
  (if ?a-1
    (cond-> {"id" ?id-1
             "objectType" "Activity"}
      (or ?def-1 ?def-2)
      (assoc "definition"
             (merge-with
              merge
              ?def-1
              (select-keys ?def-2
                           ["name"
                            "description"]))))
    a-2))

(s/fdef merge-activity
        :args (s/cat :a-1 (s/alt :nil nil?
                                 :activity ::xs/activity)
                     :a-2 ::xs/activity)
        :ret ::xs/activity)
