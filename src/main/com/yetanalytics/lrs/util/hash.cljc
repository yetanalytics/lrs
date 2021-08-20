(ns com.yetanalytics.lrs.util.hash
  #?@(:clj [(:require [clojure.java.io :as io])
            (:import [java.security MessageDigest]
                     [java.io File]
                     [java.nio ByteBuffer]
                     [java.nio.file Files]
                     )]
      :cljs [(:require
              [goog.crypt :as crypt]
              [goog.crypt.Md5 :as Md5]
              [goog.crypt.Sha1 :as Sha1]
              [goog.crypt.Sha2 :as Sha2]
              [goog.crypt.Sha256 :as Sha256]
              [goog.crypt.Sha384 :as Sha384]
              [goog.crypt.Sha512 :as Sha512])]))

#?(:cljs (defn string->bytes [s]
           (crypt/stringToUtf8ByteArray s)))

#?(:cljs (defn bytes->hex
           "convert bytes to hex"
           [bytes-in]
           (crypt/byteArrayToHex bytes-in)))

#?(:clj (defn bytes-sha-1
          "Core sha-1 implementation"
          ^String [^bytes bs]
          (apply str
                 (map
                  #(.substring
                    (Integer/toString
                     (+ (bit-and % 0xff) 0x100) 16) 1)
                  (.digest (MessageDigest/getInstance "SHA-1")
                           bs))))
   :cljs (defn bytes-sha-1
           "Core sha-1 implementation"
           [bs]
           (bytes->hex
            (.digest
             (doto (goog.crypt.Sha1.) (.update bs))))))

(defmulti sha-1 #?(:clj class
                   :cljs type))

#?(:cljs (defonce string-type (type "")))

#?(:cljs (defonce bytes-type (type (string->bytes ""))))

(defmethod sha-1 #?(:clj (Class/forName "[B")
                    :cljs bytes-type)
  ^String [^bytes bs]
  (bytes-sha-1 bs))

(defmethod sha-1 #?(:clj String
                    :cljs string-type)
  ^String [^String s]
  (bytes-sha-1 #?(:clj (.getBytes s "UTF-8")
                  :cljs (string->bytes s))))

#?(:clj (defmethod sha-1 ByteBuffer
          ^String [^ByteBuffer bb]
          (bytes-sha-1 (.array bb))))

(defmethod sha-1 :default
  [x]
  (bytes-sha-1 #?(:clj (byte-array [(hash x)])
                  :cljs (string->bytes (str (hash x))))))

#?(:clj (defn bytes-sha-256
          "Core sha-256 implementation"
          ^String [^bytes bs]
          (apply str
                 (map
                  #(.substring
                    (Integer/toString
                     (+ (bit-and % 0xff) 0x100) 16) 1)
                  (.digest (MessageDigest/getInstance "SHA-256")
                           bs))))
   :cljs (defn bytes-sha-256
           "Core sha-256 implementation"
           [bs]
           (bytes->hex
            (.digest
             (doto (goog.crypt.Sha256.) (.update bs))))))

(defmulti sha-256 #?(:clj class
                     :cljs type))

(defmethod sha-256 #?(:clj (Class/forName "[B")
                      :cljs bytes-type) ^String [^bytes bs]
  (bytes-sha-256 bs))

(defmethod sha-256 #?(:clj String
                      :cljs string-type)
  ^String [^String s]
  (bytes-sha-256 #?(:clj (.getBytes s "UTF-8")
                    :cljs (string->bytes s))))

#?(:clj (defmethod sha-256 ByteBuffer
          ^String [^ByteBuffer bb]
          (bytes-sha-256 (.array bb))))

(defmethod sha-256 :default
  [x]
  (bytes-sha-256 #?(:clj (byte-array [(hash x)])
                    :cljs (string->bytes (str (hash x))))))
