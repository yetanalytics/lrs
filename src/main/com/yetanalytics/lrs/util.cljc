(ns com.yetanalytics.lrs.util
  (:require #?@(:clj [[ring.util.codec :as codec]
                      [cheshire.core :as json]]
                :cljs [[qs]])))

(defn form-encode [params]
  #?(:clj (codec/form-encode params)
     :cljs (.stringify qs (clj->js params))))

(defn json-string [x & {:keys [pretty]
                        :or {pretty false}}]
  (if pretty
    #?(:clj (json/generate-string x {:pretty true})
       :cljs (.stringify js/JSON (clj->js x) nil 2))
    #?(:clj (json/generate-string x)
       :cljs (.stringify js/JSON (clj->js x)))))
