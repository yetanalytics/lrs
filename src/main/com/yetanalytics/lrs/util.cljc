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

;; a la https://stackoverflow.com/questions/18735665/how-can-i-get-the-positions-of-regex-matches-in-clojurescript
#?(:cljs
   (defn regex-modifiers
     "Returns the modifiers of a regex, concatenated as a string."
     [re]
     (str (if (.-multiline re) "m")
          (if (.-ignoreCase re) "i"))))

#?(:cljs
   (defn re-pos
     "Returns a vector of vectors, each subvector containing in order:
   the position of the match, the matched string, and any groups
   extracted from the match."
     [re s]
     (let [re (js/RegExp. (.-source re) (str "g" (regex-modifiers re)))]
       (loop [res []]
         (if-let [m (.exec re s)]
           (recur (conj res (vec (cons (.-index m) m))))
           res)))))
