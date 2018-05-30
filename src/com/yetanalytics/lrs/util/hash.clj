(ns com.yetanalytics.lrs.util.hash
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]
           [java.io File]
           [java.nio ByteBuffer]
           [java.nio.file Files]
           ))

(set! *warn-on-reflection* true)

(defn bytes-sha-1
  "Core sha-1 implementation"
  ^String [^bytes bs]
  (apply str
         (map
          #(.substring
            (Integer/toString
             (+ (bit-and % 0xff) 0x100) 16) 1)
          (.digest (MessageDigest/getInstance "SHA-1")
                   bs))))

(defmulti sha-1 class)

(defmethod sha-1 (Class/forName "[B") ^String [^bytes bs]
  (bytes-sha-1 bs))

(defmethod sha-1 String
  ^String [^String s]
  (bytes-sha-1 (.getBytes s "UTF-8")))

(defmethod sha-1 ByteBuffer
  ^String [^ByteBuffer bb]
  (bytes-sha-1 (.array bb)))

(defmethod sha-1 :default
  [x]
  (bytes-sha-1 (byte-array [(hash x)])))

(defn bytes-sha-256
  "Core sha-256 implementation"
  ^String [^bytes bs]
  (apply str
         (map
          #(.substring
            (Integer/toString
             (+ (bit-and % 0xff) 0x100) 16) 1)
          (.digest (MessageDigest/getInstance "SHA-256")
                   bs))))

(defmulti sha-256 class)

(defmethod sha-256 (Class/forName "[B") ^String [^bytes bs]
  (bytes-sha-256 bs))

(defmethod sha-256 String
  ^String [^String s]
  (bytes-sha-256 (.getBytes s "UTF-8")))

(defmethod sha-256 ByteBuffer
  ^String [^ByteBuffer bb]
  (bytes-sha-256 (.array bb)))

(defmethod sha-256 :default
  [x]
  (bytes-sha-256 (byte-array [(hash x)])))
