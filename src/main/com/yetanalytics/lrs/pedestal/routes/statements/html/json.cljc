(ns com.yetanalytics.lrs.pedestal.routes.statements.html.json
  "Simple json structural elements for statement data"
  (:require [clojure.walk :as w]))

(defn rendered?
  [x]
  (some-> x meta ::rendered true?))

(defn json->hiccup
  "Convert json to our hiccup html render, preserving custom-paths which is a
  map of paths in the json to functions that take the node and return normalized
  hiccup (with args map)"
  [json
   & {:keys [path
             custom-paths]
      :or {path []
           custom-paths {}}}]
  (if (rendered? json)
    ;; don't touch already rendered
    json
    (if-let [path-fn (get custom-paths path)]
      ;; if we have a custom path function, use that
      (vary-meta
       (path-fn json)
       assoc ::rendered true)
      (cond
        ;; maps are objects
        (map? json)
        (-> [:div.json.json-map]
            (into
             (map-indexed
              (fn [idx [k v]]
                [:div.json.json-map-entry
                 [:div.json.json-map-entry-key
                  (name k)]
                 [:div.json.json-map-entry-val
                  (json->hiccup v
                                :custom-paths custom-paths
                                :path (conj path k))]])
              json))
            (vary-meta assoc ::rendered true))
        ;; leave map entries alone entirely
        (map-entry? json)
        json
        ;; all other collections are arrays
        (coll? json)
        (-> [:div.json.json-array]
            (into
             (map-indexed
              (fn [idx e]
                [:div.json.json-array-element
                 (json->hiccup e
                               :custom-paths custom-paths
                               :path (conj path idx))])
              json))
            (vary-meta assoc ::rendered true))
        ;; all other scalar for now
        :else
        ^::rendered [:div.json.json-scalar (str json)]))))
