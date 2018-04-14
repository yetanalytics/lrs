(ns com.yetanalytics.lrs.xapi.document
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.java.io :as io]
            [com.yetanalytics.lrs.spec.common
             :refer [string-ascii-not-empty
                     string-alphanumeric-not-empty]]
            [cheshire.core :as json]
            [xapi-schema.spec :as xs]
            [clojure.data.priority-map :as pm]
            )
  (:import [java.time Instant]
           [java.io ByteArrayOutputStream]
           [clojure.data.priority_map PersistentPriorityMap]))

(set! *warn-on-reflection* true)

(s/def ::content-type
  string-ascii-not-empty)

(s/def ::content-length
  (s/int-in 0 Long/MAX_VALUE))

(s/def ::contents
  (s/with-gen #(satisfies? clojure.java.io/IOFactory
                          %)
    sgen/bytes))

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
     (.getBytes ^String (json/generate-string json-data)))
   (sgen/one-of
    [(s/gen ::json-map)
     (s/gen ::xs/any-json)])))

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

(defn documents-priority-map [& key-vals]
  (apply
   pm/priority-map-keyfn-by
   #(get % :updated)
   #(compare %2 %1)
   key-vals))

(s/def ::documents-priority-map
  (s/with-gen (s/and #(instance? PersistentPriorityMap %)
                     ;; It's a map of statement id to statement
                     (s/map-of ::id
                               :com.yetanalytics.lrs.xapi/document))
    (partial sgen/return (documents-priority-map))))

(s/fdef documents-priority-map
        :args (s/* (s/cat :id ::id
                          :document :com.yetanalytics.lrs.xapi/document))
        :ret ::documents-priority-map)

(def ^:dynamic *read-json-contents*
  (fn [contents]
    (with-open [rdr (io/reader contents)]
      (json/parse-stream rdr))))

(def ^:dynamic *write-json-contents*
  (fn
    ([json-map]
     (let [out (ByteArrayOutputStream. 4096)]
       (.toByteArray ^ByteArrayOutputStream (*write-json-contents*
                                             out json-map))))
    ([out json-map]
     (with-open [wtr (io/writer out)]
       (json/generate-stream json-map wtr)
       out))))

(defn merge-or-replace
  ([document]
   document)
  ([old-doc new-doc]
   (assoc
    (if (and old-doc
             (every?
              #(.startsWith ^String % "application/json")
              (map :content-type [old-doc
                                  new-doc])))
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
                   (merge old-json new-json)))
          new-doc))
      new-doc)
    :updated (str (Instant/now)))))

(s/fdef merge-or-replace
        :args (s/cat :doc-1 :com.yetanalytics.lrs.xapi/document
                     :doc-2 (s/? :com.yetanalytics.lrs.xapi/document))
        :ret :com.yetanalytics.lrs.xapi/document)


(comment


  (sgen/generate (s/gen ::documents-priority-map))
  )
