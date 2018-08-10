(ns com.yetanalytics.lrs.util.log
  (:require #?@(:clj [[io.pedestal.log :as log]]
                :cljs [[cljs.nodejs :as node]
                       [goog.string :refer [format]]
                       [goog.string.format]])))

#?(:cljs (goog-define ^boolean CLJS_LOG_DEBUG true))

#?(:cljs (defn stamp
           "return a nice little stamp for logs"
           [level]
           (format "%s %s - "
                   (.toISOString (js/Date.))
                   level)))

#?(:clj (defmacro debug [& kvs]
          `(log/debug ~@kvs))
   :cljs
   (defn debug [& kvs]
     (when CLJS_LOG_DEBUG
       (assert (even? (count kvs)) "Debug requires an even number of keys/vals")
       (try
         (.debug js/console (str
                             (stamp "DEBUG")
                             (pr-str (apply hash-map kvs))))
         (catch js/Error _
           (.log js/console (str
                             (stamp "DEBUG(as LOG)")
                             (pr-str (apply hash-map kvs)))))))))

#?(:clj (defmacro info [& kvs]
          `(log/info ~@kvs))
   :cljs
   (defn info [& kvs]
     (assert (even? (count kvs)) "Info requires an even number of keys/vals")
     (try
       (.info js/console (str
                          (stamp "INFO")
                          (pr-str (apply hash-map kvs))))
       (catch js/Error _
         (.log js/console (str
                           (stamp "INFO(as LOG)")
                           (pr-str (apply hash-map kvs))))))))

#?(:clj (defmacro warn [& kvs]
          `(log/warn ~@kvs))
   :cljs
   (defn warn [& kvs]
     (assert (even? (count kvs)) "Warn requires an even number of keys/vals")
     (.warn js/console (str
                        (stamp "WARN")
                        (pr-str (apply hash-map kvs))))))

#?(:clj (defmacro error [& kvs]
          `(log/error ~@kvs))
   :cljs
   (defn error [& kvs]
     (assert (even? (count kvs)) "Error requires an even number of keys/vals")
     (.error js/console (str
                         (stamp "ERROR")
                         (pr-str (apply hash-map kvs))))))
