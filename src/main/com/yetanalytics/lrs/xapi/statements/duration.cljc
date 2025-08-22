(ns com.yetanalytics.lrs.xapi.statements.duration
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [xapi-schema.spec :as xs]
            #?@(:cljs [[goog.string :as gstring :refer [format]]
                       [goog.string.format]])))

(s/fdef normalize-duration
  :args (s/cat :duration ::xs/duration)
  :ret ::xs/duration)

(defn normalize-duration
  "Normalize an xAPI duration string to 0.01 second precision"
  [duration]
  (let [duration-str (subs duration 1) ;; Remove 'P'
        [date-part time-part] (if (re-find #"T" duration-str)
                                (str/split duration-str #"T" 2)
                                [duration-str nil])
        ;; Parse date components
        years (when-let [m (re-find #"(\d+(?:\.\d+)?)Y" date-part)] (parse-double (second m)))
        months (when-let [m (re-find #"(\d+(?:\.\d+)?)M" date-part)] (parse-double (second m)))
        days (when-let [m (re-find #"(\d+(?:\.\d+)?)D" date-part)] (parse-double (second m)))
        ;; Parse time components
        hours (when time-part (when-let [m (re-find #"(\d+(?:\.\d+)?)H" time-part)] (parse-double (second m))))
        minutes (when time-part (when-let [m (re-find #"(\d+(?:\.\d+)?)M" time-part)] (parse-double (second m))))
        seconds (when time-part (when-let [m (re-find #"(\d+(?:\.\d+)?)S" time-part)] (parse-double (second m))))
        
        ;; Helper function to format numbers with proper precision
        format-num (fn [n unit]
                     (let [;; Truncate to 0.01 precision (round down)
                           truncated (/ (Math/floor (* n 100)) 100.0)]
                       (cond
                         ;; For seconds, handle special formatting
                         (= unit "S")
                         (cond
                           ;; Special case: when truncated to 0.00, check if original was > 0
                           (and (= truncated 0.0) (> n 0)) "0.00"
                           (= truncated 0.0) "0"
                           (= truncated (Math/floor truncated)) (str (int truncated))
                           :else (let [formatted (format "%.2f" truncated)]
                                   ;; Remove trailing zeros after decimal but keep at least one decimal place if needed
                                   (str/replace formatted #"\.?0+$" "")))
                         
                         ;; For other units, preserve integer format when possible
                         (= truncated (Math/floor truncated)) (str (int truncated))
                         :else (format "%.2f" truncated))))
        
        ;; Build the result preserving original structure when no changes needed
        date-str (str (when years (str (format-num years "Y") "Y"))
                      (when months (str (format-num months "M") "M"))
                      (when days (str (format-num days "D") "D")))
        
        time-str (when (or hours minutes seconds)
                   (str "T"
                        (when hours (str (format-num hours "H") "H"))
                        (when minutes (str (format-num minutes "M") "M"))
                        (when seconds (str (format-num seconds "S") "S"))))]
    
    (str "P" date-str time-str)))

