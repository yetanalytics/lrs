(ns com.yetanalytics.lrs.xapi.statements.timestamp
  "Code for handling and normalizing xAPI timestamps"
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            #?@(:cljs [[goog.string :as gstring :refer [format]]
                       [goog.string.format]]))
  #?(:clj (:import [java.time ZoneId Instant]
                   [java.time.format DateTimeFormatter])
     :cljs (:import [goog.date DateTime]
                    ;; for cljs repro
                    [goog.i18n DateTimeFormat TimeZone])))

;; Strict normalization of timestamp strings.
;; Intended for storage + consistency, but may be used for sortable stamp strings
(defonce #?@(:clj [^ZoneId UTC]
         :cljs [^TimeZone UTC])
  #?(:clj (ZoneId/of "UTC")
     :cljs (.createTimeZone TimeZone 0)))


#?(:clj (defonce ^DateTimeFormatter in-formatter DateTimeFormatter/ISO_DATE_TIME))

(defonce #?@(:clj [^DateTimeFormatter out-formatter]
         :cljs [^DateTimeFormat out-formatter])
  (#?(:clj DateTimeFormatter/ofPattern
      :cljs DateTimeFormat.) "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'"))

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
        (= 30 (count stamp-after))))

(defn normalize-inst
  "Normalize an inst object, ensuring that it is a static length (nano), and UTC."
  [#?@(:clj [^Instant inst]
       :cljs [inst])]
  #?(:clj (-> inst
              (Instant/from)
              (.atZone UTC)
              (->> (.format out-formatter)))
     ;; In cljs, just rely on the behavior of Date#toISOString
     :cljs (.format out-formatter inst UTC)))

(s/fdef parse-stamp
  :args (s/cat :timestamp ::xs/timestamp)
  :ret (s/tuple string? string? (s/nilable string?) string?))

(defn parse-stamp
  "return a vector of [whole-stamp body ?frac-secs offset-or-Z]"
  [timestamp]
  (re-find
   #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)$"
   timestamp))

(s/fdef normalize
  :args (s/cat :timestamp ::xs/timestamp)
  :ret ::xs/timestamp
  :fn (fn [{{stamp-before :timestamp} :args
            stamp-after :ret}]
        (= 30 (count stamp-after))))

;; PRECEPTS OF NORMALIZATION
;; * xapi-schema requires semi-strict 8601 stamps
;;   * Timestamps with offsets are currently allowed: (s/valid? ::xs/timestamp "2020-03-31T15:12:03+00:00")
;;   * Truncation possible to the second (s/valid? ::xs/timestamp "2020-03-31T15:00:00Z")
;;     * Therefore the minimum number of incoming characters is (count "2020-03-31T15:00:00Z") ;; => 20
;;   * Nine digits of precision are OK: (s/valid? ::xs/timestamp "1970-01-01T00:00:00.000000000Z")
;;     * but, um: (s/valid? ::xs/timestamp "1970-01-01T00:00:00.0000000000000000000000000Z")
;;       It's an idealistic regex I guess?
;;       * Therefore the maximum precision (and characters) is unbounded lol
;;   * does it use a conformer?
;;     * no: (s/conform ::xs/timestamp "2020-03-31T15:12:03+00:00") ;; => "2020-03-31T15:12:03+00:00"
;; * we can do some simple normalizations mechanically w/o parsing.
;; * when parsing must happen, we need to include a workaround for platform loss of precision.

(defn normalize
  "Normalize a string timestamp, ensuring that it is a static length (nano), and UTC."
  [#?@(:clj ^String [^String timestamp]
       :cljs [timestamp])]

  (let [zulu? (.endsWith timestamp "Z")
        char-count (count timestamp)]
    (cond
      ;; We can easily detect a stamp already normalized to 8601 zulu with nano
      ;; precision, and these we can let through.
      (and zulu?
           (= 30 char-count)) timestamp

      ;; if it has more than nano precision
      (and zulu?
           (< 30 char-count)) (format "%sZ"
                                      (subs timestamp 0 29))

      :else ;; we have some kind of offset. We need to parse and re-add the frac-secs
      (let [[_
             body
             ?frac-secs
             offset] (parse-stamp timestamp)]
        (if (or zulu? (= "+00:00" offset)) ;; zulu or zero-offset stamps can be handled mechanically
          (if ?frac-secs
            (format "%s.%sZ"
                    body
                    (apply str ?frac-secs
                           ;; pad
                           (repeat (- 9
                                      (count ?frac-secs))
                                   "0")))
            ;; let's add 'em
            (format "%s.000000000Z"
                    body))
          ;; if none of that is true, we have an offset, and need to parse with
          ;; the platform lib. In clojure instants are precise so we can just do
          ;; it. In cljs, we need to override it
          #?(:clj (normalize-inst (parse timestamp))
             :cljs (let [inst (parse (format "%s.000000000%s"
                                             body offset))
                         normalized (normalize-inst inst)]
                     (if ?frac-secs
                       (let [[_ body-norm _ _] (parse-stamp normalized)]
                         (format "%s.%sZ"
                                 body-norm
                                 (apply str ?frac-secs
                                        ;; pad
                                        (repeat (- 9
                                                   (count ?frac-secs))
                                                "0"))))
                       normalized))))))))

(s/fdef stamp-now
  :args (s/cat)
  :ret ::xs/timestamp)

(defn stamp-now
  "Return a timestamp for the current instant"
  []
  (normalize-inst #?(:clj (Instant/now)
                     :cljs (js/Date.))))

(s/fdef stamp-seq
  :args (s/cat)
  :ret (s/and (s/every (s/and ::xs/timestamp
                              (fn normalized?
                                [^String stamp]
                                (and (= 30 (count stamp))
                                     (.endsWith stamp "Z"))))
                       :distinct true)
              (fn monotonic? [xs]
                (= (take 10 xs) (sort (take 10 xs))))))

#?(:cljs (defonce sec-format
           (DateTimeFormat. "yyyy-MM-dd'T'HH:mm:ss")))

(defn stamp-seq
  "Return a monotonically increasing sequence of stamps.
  Monotonicity is ensured by incrementing nanos from an
  Initial timestamp"

  #?@(:clj [[& [inst]]
            (lazy-seq
             (let [^Instant inst (or inst (Instant/now))]
               (cons (normalize-inst inst)
                     (stamp-seq (.plusNanos inst 1)))))]
      :cljs [[& [t-ms nanos]]
             (lazy-seq
              (let [nanos (or nanos 0)
                    [add-ms rem-nanos] ((juxt quot rem)
                                        nanos
                                        1000000)
                    t-ms (+ (or t-ms (.getTime (js/Date.)))
                            add-ms)]
                (cons (format "%s.%09dZ"
                              (.format sec-format (js/Date. t-ms) UTC)
                              (+ (* (rem t-ms 1000) 1000000)
                                 rem-nanos))
                      (stamp-seq t-ms (inc nanos)))))]))
