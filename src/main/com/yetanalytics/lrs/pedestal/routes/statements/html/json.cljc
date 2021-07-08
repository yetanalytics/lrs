(ns com.yetanalytics.lrs.pedestal.routes.statements.html.json
  "Simple json structural elements for statement data"
  (:require [clojure.walk :as w]
            #?@(:cljs [[goog.string :refer [format]]
                       goog.string.format])))

(defn rendered?
  [x]
  (some-> x meta ::rendered true?))

(defn link-tuple?
  "Is it a tuple of link and display?"
  [x]
  (some-> x meta ::link-tuple true?))

(defn columnar?
  "Should this array be displayed as columns?"
  [x]
  (some-> x meta ::columnar true?))

(defn web-link?
  "Is it an http/s link?"
  [^String maybe-web-link]
  (or (.startsWith maybe-web-link "http://")
      (.startsWith maybe-web-link "https://")))

(defn relative-link?
  "Is it a link within the LRS?"
  [^String maybe-relative-link]
  (.startsWith maybe-relative-link "/"))

(defn linky?
  "is the string link-like?"
  [^String maybe-link]
  (or (web-link? maybe-link)
      (relative-link? maybe-link)))

(defn a-attrs
  [href]
  (cond-> {:href href}
    (web-link? href)
    (assoc :target "_blank")))1

(defn a
  [link text]
  [:a
   (a-attrs link)
   text])

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
             truncate-after-min
             truncate-after-max
             truncate-after-mod ;; int
             ]
      :or {path []
           custom {}
           key-weights {}
           ignore-custom false
           truncate-after 1
           truncate-after-min 0
           truncate-after-max 1000
           truncate-after-mod 0}}]
  (let [truncate-after
        (->> truncate-after
             (min truncate-after-max)
             (max truncate-after-min))]
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
          :truncate-after-min truncate-after-min
          :truncate-after-max truncate-after-max
          :truncate-after-mod truncate-after-mod
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
                             (a kn kn)
                             kn))
                         (json->hiccup v
                                       :custom custom
                                       :path (conj path k)
                                       :key-weights key-weights
                                       :truncate-after (+ truncate-after
                                                          truncate-after-mod)
                                       :truncate-after-min truncate-after-min
                                       :truncate-after-max truncate-after-max
                                       :truncate-after-mod truncate-after-mod)
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
          (if (link-tuple? json) ;; check for link-tuple-ness and render if so
            (let [[link text] json]
              ^::rendered
              [:div.json.json-scalar
               (a link text)])
            (let [columns? (columnar? json)
                  arr-k (if (columnar? json)
                          :div.json.json-array.columnar
                          :div.json.json-array)
                  [fel rel]
                  (->> json
                       (map-indexed
                        (fn [idx e]
                          [:div.json.json-array-element
                           (json->hiccup e
                                         :custom custom
                                         :path (conj path idx)
                                         :key-weights key-weights
                                         :truncate-after (+ truncate-after
                                                            truncate-after-mod)
                                         :truncate-after-min truncate-after-min
                                         :truncate-after-max truncate-after-max
                                         :truncate-after-mod truncate-after-mod)]))
                       (split-at truncate-after))]
              (-> (if (empty? fel)
                    [:div.json.json-array.empty]
                    (if (not-empty rel)
                      (let [truncator-id (str
                                          #?(:clj (java.util.UUID/randomUUID)
                                             :cljs (random-uuid)))]
                        (-> [arr-k]
                            (into fel)
                            (into [[:input.truncator
                                    {:type "checkbox"
                                     :id truncator-id
                                     :style "display:none;"}]
                                   [:label.truncator-label
                                    {:for truncator-id}
                                    (format "[ %d more ]" (count rel))]])))
                      (into [arr-k] fel)))
                  (into
                   rel)
                  (vary-meta assoc ::rendered true))))
          ;; all other scalar for now
          :else
          ^::rendered
          [:div.json.json-scalar
           ;; automatically create links when possible
           (if (and (string? json)
                    (linky? json))
             (a json json)
             (str json))])))))
