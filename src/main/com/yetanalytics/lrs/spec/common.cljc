(ns com.yetanalytics.lrs.spec.common
  (:require [clojure.spec.alpha :as s :include-macros true]
            [clojure.spec.gen.alpha :as sgen :include-macros true]
            [clojure.core.async :as a :include-macros true]
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

(defprotocol FakeChan
  (promise? [_])
  (state [_]))

(defn fake-chan
  "Makes a fake channel holding x, closes when x is exhausted.
   Only for blocking/sync channel calls."
  [x]
  (let [xs (atom (if (sequential? x)
                   x
                   [x]))]
    (reify
      FakeChan
      (promise? [_] (not (sequential? x)))
      (state [_] (cond-> @xs
                   (not (sequential? x))
                   first))
      ap/Channel
      (close! [_] (reset! xs [])
              nil)
      (closed? [_] (some? (seq @xs)))
      ap/ReadPort
      (take! [fchan fn1]
        (let [xs' @xs
              x (first xs')]
          (if x
            (do (swap! xs rest)
                ((ap/commit fn1) x)
                (when (seq @xs)
                  (delay x)))
            (do (ap/take! (doto (a/chan)
                            a/close!)
                          fn1))))))))

(defn conform-promise-port
  [x]
  (if (satisfies? FakeChan x)
    (state x)
    (a/<!! x)))

(defn conform-coll-port [x]
  (if (satisfies? FakeChan x)
    (state x)
    (a/<!! (a/into [] x))))

(defmacro from-port [spec]
  `(s/with-gen
     (s/and
      read-port?
      (s/conformer conform-promise-port)
      ~spec)
     (fn []
       (sgen/fmap
        fake-chan
        (s/gen ~spec)))))

(defmacro from-port-coll [spec]
  `(s/with-gen
     (s/and
      read-port?
      (s/conformer conform-coll-port)
      ~spec)
     (fn []
       (sgen/fmap
        fake-chan
        (s/gen ~spec)))))
