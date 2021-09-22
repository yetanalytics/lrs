(ns com.yetanalytics.lrs.util.log
  (:require [cljs.nodejs :as node]
            [log4js :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def assert-msg "Log function requires an even number of keys/vals")

(def logger
  (delay (let [new-logger (log/getLogger)]
           (set! (.-level new-logger)
                 (or (aget (.-env node/process)
                           "CLJS_LOG_LEVEL")
                     "INFO"))
           new-logger)))

(defn configure! [config-or-path]
  (log/configure (clj->js config-or-path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging levels
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn trace [& kvs]
  (assert (even? (count kvs)) assert-msg)
  (.trace @logger
          (pr-str (apply hash-map kvs))))

(defn debug [& kvs]
  (assert (even? (count kvs)) assert-msg)
  (.debug @logger
          (pr-str (apply hash-map kvs))))

(defn info [& kvs]
  (assert (even? (count kvs)) assert-msg)
  (.info @logger
         (pr-str (apply hash-map kvs))))

(defn warn [& kvs]
  (assert (even? (count kvs)) assert-msg)
  (.warn @logger
         (pr-str (apply hash-map kvs))))

(defn error [& kvs]
  (assert (even? (count kvs)) assert-msg)
  (.error @logger
          (pr-str (apply hash-map kvs))))

(defn fatal [& kvs]
  (assert (even? (count kvs)) assert-msg)
  (.fatal @logger
          (pr-str (apply hash-map kvs))))
