(ns com.yetanalytics.lrs.pedestal.routes.statements.html
  (:require #?@(:clj [[hiccup.core :as html]
                      [hiccup.page :as page]
                      [cheshire.core :as json]]
                :cljs [[hiccups.runtime :as hic]
                       [goog.string :refer [format]]
                       goog.string.format])
            [com.yetanalytics.lrs.pedestal.routes.statements.html.json
             :refer [json->hiccup]]))

(def page-css
  "

.json-map {
    display:grid;
}

.json-map::before {
    content:\"{\";
}

.json-map::after {
    content:\"}\";
}

.json-map-entry {
    display:grid;
    grid-template-columns: 1fr 12fr;
}

.json-map-entry-key {
    padding-left: 1em;
}

.json-map-entry-key:after {
    content: \":\";
    margin-left: 0.25em;
}


.json-map-entry-val {
}

.json-array {
    display:grid;
}

.json-array::before {
    content:\"[\";
}

.json-array::after {
    content:\"]\";
}

.json-array-element {
  padding-left: 1em;
}

.json-scalar {
  display: inline;
}

/* leaf values */
.json-map-entry-val > .json-scalar {
    background-color: cornsilk;
    text-overflow: ellipsis;
}

.json-array-element > .json-scalar {
    background-color: cornsilk;
    text-overflow: ellipsis;
}


")

(def head
  [:head
   [:style
    page-css]])


(defn page
  [hvec]
  #?(:clj (page/html5 head hvec)
     :cljs (format "<!DOCTYPE html>\n<html>%s</html>"
                   (hic/render-html
                    [head hvec]))))

(defn statement-page
  [statement]
  (page
   [:main
    (json->hiccup statement)]))

(defn statement-response
  "Given the ctx and statement, respond with a page"
  [_ statement]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (statement-page
          statement)})

(defn statements-page
  [{:keys [statements]
    ?more :more}]
  (page
   (cond-> [:main
            (into [:ul]
                  (for [{:strs [id]} statements]
                    [:li
                     [:a
                      {:href (format "/xapi/statements?statementId=%s"
                                     id)}
                      (str id)]]))
            ;; print the whole thing
            #_[:pre
             (json/generate-string
              statements
              {:pretty true})]]
     ?more
     (conj [:a
            {:href ?more}
            ?more]))))

(defn statements-response
  "Given the ctx and a statement result obj, respond with a page"
  [_ statement-result]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (statements-page
          statement-result)})
