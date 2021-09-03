(ns com.yetanalytics.lrs.xapi.document
  (:require [clojure.spec.alpha :as s :include-macros true]
            [clojure.spec.gen.alpha :as sgen :include-macros true]
            [com.yetanalytics.lrs.spec.common
             :refer [string-ascii-not-empty
                     string-alphanumeric-not-empty]]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]
            [#?(:clj clojure.data.priority-map
                :cljs tailrecursion.priority-map) :as pm]
            #?@(:clj [[cheshire.core :as json]
                      [clojure.java.io :as io]]))
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [clojure.data.priority_map PersistentPriorityMap])))

(defn updated-stamp-now []
  (timestamp/stamp-now))

(s/def ::content-type
  string-ascii-not-empty)

(s/def ::content-length
  (s/int-in 0 #?(:clj Long/MAX_VALUE
                 :cljs 9007199254740992)))

(s/def ::contents
  #?(:clj (s/with-gen #(satisfies? clojure.java.io/IOFactory
                                   %)
            sgen/bytes)
     :cljs string?))

(s/def ::updated
  :statement/timestamp)

(s/def ::id
  string-ascii-not-empty)

(s/def ::json-map
  (s/map-of string-alphanumeric-not-empty
            (s/nonconforming ::xs/any-json)))

(defn json-bytes-gen-fn []
  (sgen/fmap
   (fn [json-data]
     #?(:clj (.getBytes ^String (json/generate-string json-data))
        :cljs (.stringify js/JSON (clj->js json-data))))
   (s/gen ::json-map)))

(defn document-gen-fn []
  (sgen/fmap
   (fn [[id
         ^bytes contents
         content-type
         updated]]
     {:id id
      :contents contents
      :content-type content-type
      :content-length (count contents)
      :updated updated})
   (sgen/tuple
    (s/gen ::id)
    (s/gen ::contents)
    (s/gen ::content-type)
    (s/gen ::updated))))

(defn json-document-gen-fn []
  (sgen/fmap
   (fn [[id
         ^bytes contents
         updated]]
     {:id id
      :contents contents
      :content-type "application/json"
      :content-length (count contents)
      :updated updated})
   (sgen/tuple
    (s/gen ::id)
    (json-bytes-gen-fn)
    (s/gen ::updated))))

(s/def :com.yetanalytics.lrs.xapi/document
  (s/with-gen
    (s/keys :req-un [::content-length
                     ::content-type
                     ::contents]
            :opt-un [::id
                     ::updated])
    (fn []
      (sgen/one-of [(document-gen-fn)
                    (json-document-gen-fn)]))))
(defn updated-stamp
  [document]
  (or
   (get document :updated)
   (throw (ex-info "NO UPDATED"
                   {:type ::no-updated
                    :document document}))))

(s/fdef updated-stamp
  :args (s/cat :document :com.yetanalytics.lrs.xapi/document)
  :ret ::xs/timestamp)

(defn updated-inst
  [document]
  (timestamp/parse
   (updated-stamp document)))

(s/fdef updated-inst
  :args (s/cat :document :com.yetanalytics.lrs.xapi/document)
  :ret inst?)

(defn documents-priority-map [& key-vals]
  (apply
   pm/priority-map-keyfn-by
   updated-stamp
   #(compare %2 %1)
   key-vals))

(s/def ::documents-priority-map
  (s/with-gen (s/and #(instance? #?(:clj PersistentPriorityMap
                                    :cljs pm/PersistentPriorityMap) %)
                     ;; It's a map of statement id to statement
                     (s/map-of ::id
                               :com.yetanalytics.lrs.xapi/document))
    (partial sgen/return (documents-priority-map))))

(s/fdef documents-priority-map
        :args (s/* (s/cat :id ::id
                          :document :com.yetanalytics.lrs.xapi/document))
        :ret ::documents-priority-map)

(defn throw-read-json-error []
  (throw (ex-info "Cannot read JSON"
                  {:type ::json-read-error})))

(defn throw-json-not-object-error [json-result]
  (throw (ex-info "JSON is not an object"
                  {:type ::json-not-object-error
                   :json json-result})))

(def ^:dynamic *read-json-contents*
  (fn [contents]
    (let [[result ?more] #?(:clj (try (with-open [rdr (io/reader contents)]
                                        (doall (json/parsed-seq rdr)))
                                      (catch com.fasterxml.jackson.core.JsonParseException jpe
                                        (throw-read-json-error)))
                            :cljs (try [(js->clj (.parse js/JSON contents))
                                        nil]
                                       (catch js/Error _
                                         (throw-read-json-error))))]
      (if (and (map? result) (nil? ?more))
        result
        (throw-json-not-object-error result)))))

(def ^:dynamic *write-json-contents*
  (fn
    ([json-map]
     #?(:clj (let [out (ByteArrayOutputStream. 4096)]
               (.toByteArray ^ByteArrayOutputStream (*write-json-contents*
                                                     out json-map)))
        :cljs (.stringify js/JSON (clj->js json-map))))
    ([out json-map]
     #?(:clj (with-open [wtr (io/writer out)]
               (json/generate-stream json-map wtr)
               out)
        :cljs (.stringify js/JSON (clj->js json-map))))))

(defn merge-or-replace
  ([{:keys [contents
            content-type] :as document}]
   (assoc (if (and content-type
                   (.startsWith ^String content-type "application/json"))
            ;; JSON documents must be de/reseriealized
            (let [json (*read-json-contents* contents)
                  reserialized-bytes (*write-json-contents* json)]
              (assoc document
                     :content-length (count reserialized-bytes)
                     :contents reserialized-bytes))
            document)
          :updated
          (updated-stamp-now)))
  ([old-doc new-doc]
   (if old-doc
     (assoc
      (cond
        (every?
         #(.startsWith ^String % "application/json")
         (map :content-type [old-doc
                             new-doc]))
        (let [old-json (*read-json-contents*
                        (:contents old-doc))
              new-json (*read-json-contents*
                        (:contents new-doc))]
          (if (every? map? [old-json new-json])
            (let [merged-bytes (*write-json-contents*
                                (merge old-json new-json))]
              (assoc new-doc
                     :content-length (count merged-bytes)
                     :contents
                     merged-bytes))
            new-doc))
        (not (every?
              #(.startsWith ^String % "application/json")
              (map :content-type [old-doc
                                  new-doc])))
        (throw (ex-info "Attempt to merge documents of different content types"
                        {:type ::invalid-merge
                         :old-doc old-doc
                         :new-doc new-doc})))
      :updated (updated-stamp-now))
     (merge-or-replace new-doc))))

(s/fdef merge-or-replace
        :args (s/cat :doc-1 (s/with-gen :com.yetanalytics.lrs.xapi/document
                              json-document-gen-fn)
                     :doc-2 (s/? (s/with-gen :com.yetanalytics.lrs.xapi/document
                                   json-document-gen-fn)))
        :ret :com.yetanalytics.lrs.xapi/document
        :fn
        (fn merges-or-returns?
          [{ret :ret
            {doc-1 :doc-1 doc-2 :doc-2} :args}]
          (if (and doc-1 doc-2)
            (= (*read-json-contents* (:contents ret))
               (merge (*read-json-contents* (:contents doc-1))
                      (*read-json-contents* (:contents doc-2))))
            (= (*read-json-contents* (:contents doc-1))
               (*read-json-contents* (:contents ret))))))
