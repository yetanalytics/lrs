(ns com.yetanalytics.lrs.pedestal.routes.statements.html
  (:require [hiccup.core :as html]
            [hiccup.page :as page]
            [cheshire.core :as json]))

(defn statement-page
  [statement]
  (page/html5
   [:main
    [:pre
     (json/generate-string
      statement
      {:pretty true})]]))

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
  (page/html5
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
