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

(def apply-custom-paths
  (partial
   reduce
   (fn [json [path path-fn]]
     (update-in json path (comp
                           #(vary-meta % assoc ::rendered true)
                           path-fn)))))

(defn json->hiccup
  "Convert json to our hiccup html render, preserving custom-paths which is a
  map of paths in the json to functions that take the node and return hiccup"
  [json
   & {:keys [custom-paths]
      :or {custom-paths {}}}]
  (-> json
      (apply-custom-paths custom-paths)
      (->>
       (w/postwalk
        (fn [node]
          (if (some-> node meta ::rendered)
            ;; don't touch already rendered
            node
            (cond
              ;; maps are objects
              (map? node)
              (into
               ^::rendered [:div.json-map]
               (map
                (fn [[k v]]
                  ^::rendered [:div.json-map-entry
                               ^::rendered [:div.json-map-entry-key k]
                               ^::rendered [:div.json-map-entry-val v]])
                node))
              (map-entry? node)
              node
              ;; all other collections are arrays
              (coll? node)
              (into
               ^::rendered [:div.json-array]
               (map
                (fn [n]
                  ^::rendered [:div.json-array-element n])
                node))
              ;; all other scalar for now
              :else
              (if (contains? el-keys
                             node)
                node
                [:div.json-scalar (str node)]))))))))
