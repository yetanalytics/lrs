(ns com.yetanalytics.lrs.pedestal.routes.statements.html.json
  "Simple json structural elements for statement data"
  (:require [clojure.walk :as w]))

(def el-keys
  #{:div.json-map
    :div.json-map-entry
    :div.json-map-entry-key
    :div.json-map-entry-val
    :div.json-array
    :div.json-array-element})

(defn json->hiccup
  [json]
  (w/postwalk
   (fn [node]
     (cond
       ;; maps are objects
       (map? node)
       (into
        [:div.json-map]
        (map
         (fn [[k v]]
           [:div.json-map-entry
            [:div.json-map-entry-key k]
            [:div.json-map-entry-val v]])
         node))
       (map-entry? node)
       node
       ;; all other collections are arrays
       (coll? node)
       (into
        [:div.json-array]
        (map-indexed
         (partial vector :div.json-array-element)
         node))
       ;; all other scalar for now
       :else
       (if (contains? el-keys
                      node)
         node
         [:div.json-scalar (str node)])))
   json))
