(ns com.yetanalytics.lrs.pedestal.routes.statements.html
  (:require #?@(:clj [[hiccup.core :as html]
                      [hiccup.page :as page]
                      [cheshire.core :as json]
                      [clojure.java.io :as io]]
                :cljs [[hiccups.runtime :as hic]
                       [goog.string :refer [format]]
                       goog.string.format])
            [com.yetanalytics.lrs.pedestal.routes.statements.html.json
             :refer [json->hiccup]])
  #?(:cljs (:require-macros [com.yetanalytics.lrs.pedestal.routes.statements.html
                             :refer [load-css!]])))

#?(:clj (defmacro load-css! []
          (slurp (io/resource "lrs/statements/style.css"))))

(defonce page-css
  (load-css!))

(def head
  [:head
   [:style
    page-css]])

(defn page
  [hvec]
  #?(:clj (page/html5 head hvec)
     :cljs (format "<!DOCTYPE html>\n<html>%s</html>"
                   (hic/render-html
                    (list head hvec)))))

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
