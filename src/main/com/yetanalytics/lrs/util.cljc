(ns com.yetanalytics.lrs.util
  (:require #?@(:clj [[ring.util.codec :as codec]
                      [cheshire.core :as json]]
                :cljs [[qs]])))

(defn form-encode [params]
  #?(:clj (codec/form-encode params)
     :cljs (.stringify qs (clj->js params))))

(defn json-string [x]
  #?(:clj (json/generate-string x)
     :cljs (.stringify js/JSON (clj->js x))))
