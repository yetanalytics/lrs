(ns com.yetanalytics.lrs.pedestal.routes.statements.html.json
  "Simple json structural elements for statement data"
  (:require [com.yetanalytics.lrs.util :as u]
            [clojure.string :as cs]
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

(defn ->data-attrs
  [data]
  (reduce-kv
   (fn [m k v]
     (assoc
      m
      (keyword
       nil
       (format
        "data-%s"
        (name k)))
      v))
   (empty data)
   data))

(defn inject-attrs
  "Inject attributes into a hiccup structure that may or may not have them."
  [[el-k fr & rr :as hiccup]
   attrs]
  (with-meta
    (let [attrs (or attrs {})]
      (if (map? fr)
        (into [el-k (merge fr attrs)] rr)
        (into [el-k attrs] (cons fr rr))))
    (meta hiccup)))

(defn- node-data-attrs
  "Commonly"
  [json path]
  (->data-attrs
   (cond-> {:path (cs/join
                   ","
                   (map
                    #(if (keyword? %)
                       (name %)
                       (str %))
                    path))}
     (coll? json) (assoc
                   :count (str (count json)))
     ;; include json for maps so we can generate a CSS <PRE> for instance
     (map? json) (assoc
                  :json (u/json-string json :pretty true)))))

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
             url-params ;; passthru for a map of params for linking
             ]
      :or {path []
           custom {}
           key-weights {}
           ignore-custom false
           truncate-after 1
           truncate-after-min 0
           truncate-after-max 1000
           truncate-after-mod 0
           url-params {}}}]
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
        (-> (custom-fn
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
             :url-params url-params)
         (inject-attrs
          (node-data-attrs json path))
         (vary-meta
          assoc ::rendered true))
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
                        (let [kn (name k)
                              scalar? (and (not (rendered? v))
                                           (or (link-tuple? v)
                                               (not (coll? v))))]
                          (-> (json-map-entry
                               (if (linky? kn)
                                 (a kn kn)
                                 kn)
                               (json->hiccup v
                                             :custom custom
                                             :path (conj path k)
                                             :key-weights key-weights
                                             :truncate-after (+ truncate-after
                                                                truncate-after-mod)
                                             :truncate-after-min truncate-after-min
                                             :truncate-after-max truncate-after-max
                                             :truncate-after-mod truncate-after-mod
                                             :url-params url-params)
                               :scalar scalar?)
                              ;; inject name of key
                              (inject-attrs (->data-attrs {:entry-key-name kn
                                                           :entry-idx (str idx)}))))))
                     (split-at truncate-after))]
            (-> (if (empty? fent)
                  [:div.json.json-map.empty]
                  (if (not-empty rent)
                    (let [truncator-id (str
                                        #?(:clj (java.util.UUID/randomUUID)
                                           :cljs (random-uuid)))
                          rent-count (count rent)]
                      (-> [:div.json.json-map]
                          (into fent)
                          (into
                           [[:input.truncator
                             {:type "checkbox"
                              :id truncator-id
                              :style "display:none;"}]
                            [(if (< 1 rent-count)
                               :label.truncator-label.plural
                               :label.truncator-label)
                             {:for truncator-id}
                             (format "%d" (count rent))]])))
                    (into [:div.json.json-map] fent)))
                (into
                 rent)
                (inject-attrs
                 (node-data-attrs json path))
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
                           (->data-attrs {:element-idx (str idx)})
                           (json->hiccup e
                                         :custom custom
                                         :path (conj path idx)
                                         :key-weights key-weights
                                         :truncate-after (+ truncate-after
                                                            truncate-after-mod)
                                         :truncate-after-min truncate-after-min
                                         :truncate-after-max truncate-after-max
                                         :truncate-after-mod truncate-after-mod
                                         :url-params url-params)]))
                       (split-at truncate-after))]
              (-> (if (empty? fel)
                    [:div.json.json-array.empty]
                    (if (not-empty rel)
                      (let [truncator-id (str
                                          #?(:clj (java.util.UUID/randomUUID)
                                             :cljs (random-uuid)))
                            rel-count (count rel)]
                        (-> [arr-k]
                            (into fel)
                            (into [[:input.truncator
                                    {:type "checkbox"
                                     :id truncator-id
                                     :style "display:none;"}]
                                   [(if (< 1 rel-count)
                                      :label.truncator-label.plural
                                      :label.truncator-label)
                                    {:for truncator-id}
                                    (format "%d" (count rel))]])))
                      (into [arr-k] fel)))
                  (into
                   rel)
                  (inject-attrs
                   (node-data-attrs json path))
                  (vary-meta assoc ::rendered true))))
          ;; all other scalar for now
          :else
          ^::rendered
          [:div.json.json-scalar
           (merge (node-data-attrs json path)
                  {:data-scalar-value (str json)})
           ;; automatically create links when possible
           (if (and (string? json)
                    (linky? json))
             (a json json)
             (str json))])))))
