(ns com.yetanalytics.lrs.spec.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]))

(def string-ascii-not-empty
  (s/with-gen (s/and string?
                     not-empty)
    (fn []
      (sgen/not-empty
       (sgen/string-ascii)))))

(def string-alphanumeric-not-empty
  (s/with-gen (s/and string?
                     not-empty)
    (fn []
      (sgen/not-empty
       (sgen/string-alphanumeric)))))

(defn with-conform-gen
  "Return a version of the spec that generates then conforms"
  [spec]
  (s/with-gen spec
    (fn []
      (sgen/fmap (partial s/conform spec)
                 (s/gen spec)))))

;; Async Spec Helpers

(defn read-port?
  "Predicate to see if something is a read-port?"
  [c]
  (satisfies? ap/ReadPort c))

(defn fake-chan
  "Makes a fake channel holding x"
  [x]
  (reify
    ap/ReadPort
    (take! [_ _]
      (delay x))))

(defmacro from-port [spec]
  `(s/with-gen
     (s/and
      read-port?
      (s/conformer a/<!!)
      ~spec)
     (fn []
       (sgen/fmap
        fake-chan
        (s/gen ~spec)))))
