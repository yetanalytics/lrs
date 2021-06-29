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
  [path-prefix
   json & json->hiccup-args]
  (conj (apply json->hiccup
               json
               :ignore-custom true
               json->hiccup-args)
        [:div.json.json-map-action.no-truncate
         [:div.json.json-scalar
          [:a {:href (format
                      "%s/statements?agent=%s"
                      path-prefix
                      (encode-query-part
                       #?(:clj (json/generate-string
                                json)
                          :cljs (.stringify js/JSON (clj->js json)))))}
           "Filter..."]]]))

(defn verb-pred
  [path json]
  (contains? #{["verb"]
               ["object" "verb"]}
             path))

(defn verb-custom
  [path-prefix
   json & json->hiccup-args]
  (conj (apply json->hiccup
               json
               :ignore-custom true
               json->hiccup-args)
        [:div.json.json-map-action.no-truncate
         [:div.json.json-scalar
          [:a
           {:href (format
                   "%s/statements?verb=%s"
                   path-prefix
                   (encode-query-part (get json "id")))}
           "Filter..."]]]))

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
  [path-prefix
   json & json->hiccup-args]
  (conj (apply json->hiccup
               json
               :ignore-custom true
               json->hiccup-args)
        [:div.json.json-map-action.no-truncate
         [:div.json.json-scalar
          [:a
           {:href (format
                   "%s/statements?activity=%s"
                   path-prefix
                   (encode-query-part (get json "id")))}
           "Filter..."]]]))

(defn reg-pred [path _json]
  (= "registration" (peek path)))

(defn reg-custom [path-prefix
                  json & _]
  [:div.json.json-scalar
   [:a
    {:href (format
            "%s/statements?registration=%s"
            path-prefix
            json)}
    json]])

(defn ref-pred [path json]
  (and (map? json)
       (= "StatementRef" (get json "objectType"))))

(defn ref-custom [path-prefix
                  json & json->hiccup-args]
  (conj (apply json->hiccup
               json
               :ignore-custom true
               json->hiccup-args)
        [:div.json.json-map-action.no-truncate
         [:div.json.json-scalar
          [:a
           {:href (format
                   "%s/statements?statementId=%s"
                   path-prefix
                   (get json "id"))}
           "View..."]]]))

(defn statement-custom*
  [path-prefix]
  {;; linkable actors
   actor-pred
   (partial actor-custom
            path-prefix)
   ;; linkable verbs
   verb-pred
   (partial verb-custom
            path-prefix)
   ;; linkable activities
   activity-pred
   (partial activity-custom
            path-prefix)
   ;; linkable registration
   reg-pred
   (partial reg-custom
            path-prefix)
   ;; linkable statement ref
   ref-pred
   (partial ref-custom
            path-prefix)})

(def statement-custom
  (memoize statement-custom*))

(def statement-key-weights
  {"id" 2
   "mbox" 1
   "mbox_sha1sum" 1
   "openid" 1
   "account" 1})

(defn statement-page
  [path-prefix
   statement]
  (page
   [:main.statement
    (json->hiccup
     statement
     :custom (statement-custom path-prefix)
     :key-weights statement-key-weights)]))

(defn statement-response
  "Given the ctx and statement, respond with a page"
  [{path-prefix :com.yetanalytics.lrs.pedestal.interceptor/path-prefix
    :or {path-prefix ""}}
   statement]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (statement-page
          path-prefix
          statement)})

(defn statements-page
  [path-prefix
   {:keys [statements]
    ?more :more}]
  (page
   (cond-> [:main.statement-response.json.json-map
            [:div.json.json-map-entry.statements
             [:div.json.json-map-entry-key
              "statements"]
             [:div.json.json-map-entry-val
              (let [[fel & rel] (for [{:strs [id] :as statement} statements]
                                  [:div.json.json-array-element
                                   (json->hiccup
                                    statement
                                    :custom (statement-custom path-prefix)
                                    :key-weights statement-key-weights)])]
                (into (if-not fel
                        [:div.json.json-array.statements.empty]
                        (if (not-empty rel)
                          (let [truncator-id (str
                                              #?(:clj (java.util.UUID/randomUUID)
                                                 :cljs (random-uuid)))]
                            [:div.json.json-array.statements
                             fel
                             [:input.truncator
                              {:type "checkbox"
                               :id truncator-id
                               :style "display:none;"}]
                             [:label.truncator-label
                              {:for truncator-id}
                              (format "[ %d more ]" (count rel))]])
                          [:div.json.json-array.statements fel]))
                      rel))]]]
     ?more
     (conj
      [:div.json.json-map-entry.more
       [:div.json.json-map-entry-key
        "more"]
       [:div.json.json-map-entry-val
        [:div.json.json-scalar
         [:a.more
          {:href ?more}
          ?more]]]]))))

(defn statements-response
  "Given the ctx and a statement result obj, respond with a page"
  [{path-prefix :com.yetanalytics.lrs.pedestal.interceptor/path-prefix
    :or {path-prefix ""}}
   statement-result]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (statements-page
          path-prefix
          statement-result)})
