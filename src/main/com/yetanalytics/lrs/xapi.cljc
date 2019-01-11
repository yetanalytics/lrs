(ns com.yetanalytics.lrs.xapi
  (:require
   [clojure.spec.alpha :as s :include-macros true]
   [clojure.spec.gen.alpha :as sgen :include-macros true]
   [xapi-schema.spec.resources :as xsr]
   [xapi-schema.spec :as xs]
   [com.yetanalytics.lrs.spec.common :as sc]
   [com.yetanalytics.lrs.util.hash :refer [sha-1 bytes-sha-1]]))

(s/def ::etag
  (s/with-gen
    (s/and string?
           #(= 40 (count %)))
    (fn []
      (sgen/fmap bytes-sha-1
                 #?(:clj (sgen/bytes)
                    :cljs (sgen/string))))))
