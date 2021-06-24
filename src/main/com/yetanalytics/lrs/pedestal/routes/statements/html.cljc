(ns com.yetanalytics.lrs.pedestal.routes.statements.html
  (:require #?@(:clj [[hiccup.core :as html]
                      [hiccup.page :as page]
                      [cheshire.core :as json]
                      [clojure.java.io :as io]]
                :cljs [[hiccups.runtime :as hic]
                       [goog.string :refer [format]]
                       goog.string.format])
            [com.yetanalytics.lrs.pedestal.routes.statements.html.json
             :as jr
             :refer [json->hiccup json-map-entry]])
  #?(:cljs (:require-macros [com.yetanalytics.lrs.pedestal.routes.statements.html
                             :refer [load-css!]]))
  #?(:clj (:import [java.net URLEncoder])))

(defn- encode-query-part
  [^String qpart]
  #?(:clj (URLEncoder/encode qpart "UTF-8")
     :cljs (js/encodeURIComponent qpart)))

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

(defn actor-pred
  [path json]
  (and
   (map? json)
   (or (contains? #{["actor"]
                    ["object" "actor"]}
                  path)
       (contains? #{"Agent" "Group"}
                  (get json "objectType")))
   (some #{"mbox" "mbox_sha1sum" "openid" "account"}
         (keys json))))

(defn actor-custom
  [json & _]
  (conj (json->hiccup json)
        (json-map-entry
         ""
         [:div.json.json-scalar
          [:a {:href (format
                      "/xapi/statements?agent=%s"
                      (encode-query-part
                       #?(:clj (json/generate-string
                                json)
                          :cljs (.stringify js/JSON (clj->js json)))))}
           "Filter..."]])))

(defn verb-pred
  [path json]
  (contains? #{["verb"]
               ["object" "verb"]}
             path))

(defn verb-custom
  [json & _]
  (conj (json->hiccup json)
        (json-map-entry
         ""
         [:div.json.json-scalar
          [:a
           {:href (format
                   "/xapi/statements?verb=%s"
                   (encode-query-part (get json "id")))}
           "Filter..."]])))

(defn activity-pred
  [path json]
  (or (and
       (contains? #{["object"]
                    ["object" "object"]}
                  path)
       (nil? (get json "objectType"))
       (get json "id"))
      (and (map? json)
           (= "Activity" (get json "objectType")))))
(defn activity-custom
  [json & _]
  (conj (json->hiccup json)
        (json-map-entry
         ""
         [:div.json.json-scalar
          [:a
           {:href (format
                   "/xapi/statements?activity=%s"
                   (encode-query-part (get json "id")))}
           "Filter..."]])))

(defn reg-pred [path _json]
  (= "registration" (peek path)))

(defn reg-custom [json & _]
  [:div.json.json-scalar
   [:a
    {:href (format
            "/xapi/statements?registration=%s"
            json)}
    json]])

(defn ref-pred [path json]
  (and (map? json)
       (= "StatementRef" (get json "objectType"))))

(defn ref-custom [json & _]
  (conj (json->hiccup json)
        (json-map-entry
         ""
         [:div.json.json-scalar
          [:a
           {:href (format
                   "/xapi/statements?statementId=%s"
                   (get json "id"))}
           "View..."]])))

(def statement-custom
  {;; linkable actors
   actor-pred
   actor-custom
   ;; linkable verbs
   verb-pred
   verb-custom
   ;; linkable activities
   activity-pred
   activity-custom
   ;; linkable registration
   reg-pred
   reg-custom
   ;; linkable statement ref
   ref-pred
   ref-custom})

(defn statement-page
  [statement]
  (page
   [:main
    (json->hiccup
     statement
     :custom
     statement-custom)]))

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
                  (for [{:strs [id] :as statement} statements]
                    [:li
                     (jr/collapse-wrapper
                      (str id)
                      (json->hiccup
                       statement
                       :custom
                       statement-custom))
                     ]))]
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
