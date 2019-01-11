(ns com.yetanalytics.lrs.pedestal.interceptor.util
  (:require [clojure.java.io :as io]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log])
  (:import [java.io InputStream]))


(defn drain-input-stream
  "Drain an input stream that still has stuff left."
  [^InputStream is]
  (let [available (.available is)]
    (when (< 0 available)
      (log/debug :msg "Draining unused request body"
                 :available-bytes available)
      (slurp is))))

(def ensure-body-drained
  "Interceptor to ensure that the request body is drained of content."
  {:name ::ensure-body-drained
   :leave (fn [ctx]
            (when (some-> ctx :response :status (>= 400))
              (some-> ctx :request :body drain-input-stream))
            ctx)})
