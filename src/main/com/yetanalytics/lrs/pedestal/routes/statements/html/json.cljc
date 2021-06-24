(ns com.yetanalytics.lrs.pedestal.routes.statements.html.json
  "Simple json structural elements for statement data"
  (:require [clojure.walk :as w]))

(defn rendered?
  [x]
  (some-> x meta ::rendered true?))

(defn linky?
  "is the string link-like?"
  [^String maybe-link]
  (or (.startsWith maybe-link "http://")
      (.startsWith maybe-link "https://")))

(defn json-map-entry
  "Helper for creating map entries"
  [k v]
  [:div.json.json-map-entry
   [:div.json.json-map-entry-key
    k]
   [:div.json.json-map-entry-val
    v]])

(defn json->hiccup
  [json
   & {:keys [path
             custom]
      :or {path []
           custom {}}}]
  (if (rendered? json)
    ;; don't touch already rendered
    json
    (if-let [custom-fn (some
                        (fn [[pred cfn]]
                          (when (pred path
                                      json)
                            cfn))
                        custom)]
      ;; if we have a custom path function, use that
      (vary-meta
       (custom-fn
        json
        ;; throw the json->hiccup args on the end
        ;; possibly useful to resume
        :custom custom
        :path path
        )
       assoc ::rendered true)
      (cond
        ;; maps are objects
        (map? json)
        (-> [:div.json.json-map]
            (into
             (map-indexed
              (fn [idx [k v]]
                (json-map-entry
                 (let [kn (name k)]
                   (if (linky? kn)
                     [:a
                      {:href kn
                       :target "_blank"}
                      kn]
                     kn))
                 (json->hiccup v
                               :custom custom
                               :path (conj path k))))
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
                               :custom custom
                               :path (conj path idx))])
              json))
            (vary-meta assoc ::rendered true))
        ;; all other scalar for now
        :else
        ^::rendered
        [:div.json.json-scalar
         ;; automatically create links when possible
         (if (and (string? json)
                  (linky? json))
           [:a
            {:href json
             :target "_blank"}
            json]
           (str json))]))))
