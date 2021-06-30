(ns com.yetanalytics.lrs.pedestal.routes.statements.html.json
  "Simple json structural elements for statement data"
  (:require [clojure.walk :as w]
            #?@(:cljs [[goog.string :refer [format]]
                       goog.string.format])))

(defn rendered?
  [x]
  (some-> x meta ::rendered true?))

(defn linky?
  "is the string link-like?"
  [^String maybe-link]
  (or (.startsWith maybe-link "http://")
      (.startsWith maybe-link "https://")
      (.startsWith maybe-link "/") ;; catch more link
      ))

(defn json-map-entry
  "Helper for creating map entries"
  [k v & {:keys [scalar]
          :or {scalar false}}]
  [:div.json.json-map-entry
   [:div.json.json-map-entry-key
    k]
   [(if scalar
      :div.json.json-map-entry-val.scalar
      :div.json.json-map-entry-val)
    v]])

(defn json->hiccup
  [json
   & {:keys [path
             custom
             key-weights ;; map of key to number, higher is higher
             ignore-custom ;; ignore custom, one level deep
             truncate-after ;; truncate after this many collection elements
             ]
      :or {path []
           custom {}
           key-weights {}
           ignore-custom false
           truncate-after 1}}]
  (if (rendered? json)
    ;; don't touch already rendered
    json
    (if-let [custom-fn (and (not ignore-custom)
                            (some
                             (fn [[pred cfn]]
                               (when (pred path
                                           json)
                                 cfn))
                             custom))]
      ;; if we have a custom path function, use that
      (vary-meta
       (custom-fn
        json
        ;; throw the json->hiccup args on the end
        ;; possibly useful to resume
        :custom custom
        :path path
        :key-weights key-weights
        :truncate-after truncate-after
        )
       assoc ::rendered true)
      (cond
        ;; maps are objects
        (map? json)
        (let [[fent rent]
              (->> json
                   (sort-by
                    #(get key-weights (first %) 0)
                    >)
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
                                     :path (conj path k)
                                     :key-weights key-weights
                                     :truncate-after truncate-after)
                       :scalar (not (coll? v)))))
                   (split-at truncate-after))]
          (-> (if (empty? fent)
                [:div.json.json-map.empty]
                (if (not-empty rent)
                  (let [truncator-id (str
                                      #?(:clj (java.util.UUID/randomUUID)
                                         :cljs (random-uuid)))]
                    (-> [:div.json.json-map]
                        (into fent)
                        (into
                         [[:input.truncator
                           {:type "checkbox"
                            :id truncator-id
                            :style "display:none;"}]
                          [:label.truncator-label
                           {:for truncator-id}
                           (format "{ %d more }" (count rent))]])))
                  (into [:div.json.json-map] fent)))
              (into
               rent)
              (vary-meta assoc ::rendered true)))
        ;; leave map entries alone entirely
        (map-entry? json)
        json
        ;; all other collections are arrays
        (coll? json)
        (let [[fel rel]
              (->> json
                   (map-indexed
                    (fn [idx e]
                      [:div.json.json-array-element
                       (json->hiccup e
                                     :custom custom
                                     :path (conj path idx)
                                     :key-weights key-weights
                                     :truncate-after truncate-after)]))
                   (split-at truncate-after))]
          (-> (if (empty? fel)
                [:div.json.json-array.empty]
                (if (not-empty rel)
                  (let [truncator-id (str
                                      #?(:clj (java.util.UUID/randomUUID)
                                         :cljs (random-uuid)))]
                    (-> [:div.json.json-array]
                        (into fel)
                        (into [[:input.truncator
                                {:type "checkbox"
                                 :id truncator-id
                                 :style "display:none;"}]
                               [:label.truncator-label
                                {:for truncator-id}
                                (format "[ %d more ]" (count rel))]])))
                  (into [:div.json.json-array] fel)))
            (into
             rel)
            (vary-meta assoc ::rendered true)))
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
