(ns com.yetanalytics.lrs.util.log
  (:require [cljs.nodejs :as node]
            [goog.string.format]
            [log4js :as log]))

(def logger
  (delay (let [new-logger (log/getLogger)]
           (set! (.-level new-logger)
                 (or (aget (.-env node/process)
                           "CLJS_LOG_LEVEL")
                     "INFO"))
           new-logger)))

(defn configure! [config-or-path]
  (log/configure (clj->js config-or-path)))


(defn trace [& kvs]
  (assert (even? (count kvs)) "Log function requires an even number of keys/vals")
  (.trace @logger
          (pr-str (apply hash-map kvs))))

(defn debug [& kvs]
  (assert (even? (count kvs)) "Log function requires an even number of keys/vals")
  (.debug @logger
          (pr-str (apply hash-map kvs))))

(defn info [& kvs]
  (assert (even? (count kvs)) "Log function requires an even number of keys/vals")
  (.info @logger
         (pr-str (apply hash-map kvs))))

(defn warn [& kvs]
  (assert (even? (count kvs)) "Log function requires an even number of keys/vals")
  (.warn @logger
         (pr-str (apply hash-map kvs))))

(defn error [& kvs]
  (assert (even? (count kvs)) "Log function requires an even number of keys/vals")
  (.error @logger
          (pr-str (apply hash-map kvs))))

(defn fatal [& kvs]
  (assert (even? (count kvs)) "Log function requires an even number of keys/vals")
  (.fatal @logger
          (pr-str (apply hash-map kvs))))
