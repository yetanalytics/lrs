(ns com.yetanalytics.lrs.xapi.statements.timestamp
  "Code for handling and normalizing xAPI timestamps"
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs])
  #?(:clj (:import [java.time ZoneId Instant]
                   [java.time.format DateTimeFormatter])
     :cljs (:import [goog.date DateTime])))

#?(:clj (set! *warn-on-reflection* true))

;; Strict normalization of timestamp strings.
;; Intended for storage + consistency, but may be used for sortable stamp strings
#?(:clj (def ^ZoneId UTC (ZoneId/of "UTC")))
#?(:clj (def ^DateTimeFormatter in-formatter DateTimeFormatter/ISO_DATE_TIME))
#?(:clj (def ^DateTimeFormatter out-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")))

;; parse xAPI timestamps
(s/fdef parse
  :args (s/cat :timestamp ::xs/timestamp)
  :ret inst?)

(defn parse
  "Parse an xAPI timestamp string"
  [#?@(:clj [^String timestamp]
       :cljs [timestamp])]
  #?(:clj (-> (.parse in-formatter timestamp)
              (Instant/from)
              (.atZone UTC)
              .toInstant)
     ;; In cljs, just rely on the behavior of Date#toISOString
     :cljs (js/Date. (.parse js/Date timestamp))))


;; inst/date to normalized string
(s/fdef normalize-inst
  :args (s/cat :inst inst?)
  :ret ::xs/timestamp
  :fn (fn [{stamp-after :ret}]
        (= 24 (count stamp-after))))

(defn normalize-inst
  "Normalize an inst object, ensuring that it is a static length, and UTC."
  [#?@(:clj [^Instant inst]
       :cljs [inst])]
  #?(:clj (-> inst
              (Instant/from)
              (.atZone UTC)
              (->> (.format out-formatter)))
     ;; In cljs, just rely on the behavior of Date#toISOString
     :cljs (.toISOString inst)))

(s/fdef normalize
  :args (s/cat :timestamp ::xs/timestamp)
  :ret ::xs/timestamp
  :fn (fn [{{stamp-before :timestamp} :args
            stamp-after :ret}]
        (= 24 (count stamp-after))))

(defn normalize
  "Normalize a string timestamp, ensuring that it is a static length, and UTC."
  [#?@(:clj [^String timestamp]
       :cljs [timestamp])]
  (normalize-inst
   (parse timestamp)))
