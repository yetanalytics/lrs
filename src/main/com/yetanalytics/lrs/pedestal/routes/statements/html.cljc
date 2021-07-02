(ns com.yetanalytics.lrs.pedestal.routes.statements.html
  (:require #?@(:clj [[hiccup.core :as html]
                      [hiccup.page :as page]
                      [cheshire.core :as json]
                      [clojure.java.io :as io]]
                :cljs [[hiccups.runtime :as hic]
                       [goog.string :refer [format]]
                       goog.string.format])
            [clojure.string :as cs]
            [com.yetanalytics.lrs.pedestal.routes.statements.html.json
             :as jr
             :refer [json->hiccup json-map-entry]]
            [com.yetanalytics.lrs.util :as u])
  #?(:cljs (:require-macros [com.yetanalytics.lrs.pedestal.routes.statements.html
                             :refer [load-css!]]))
  #?(:clj (:import [java.net URLEncoder])))

(defn- statements-link
  [path-prefix
   params]
  (str path-prefix "/statements"
       (when (not-empty params)
         (str
          "?"
          (u/form-encode
           (cond-> params
             (:agent params) (update :agent u/json-string)))))))

(defn- unwrap?
  "Given the pedestal context, is the user asking for unwrapped html?"
  [{{{:keys [unwrap_html]} :params} :request}]
  (some-> unwrap_html cs/lower-case (= "true")))

#?(:clj (defmacro load-css! []
          (slurp (io/resource "lrs/statements/style.css"))))

(defonce page-css
  (load-css!))

(def head
  [:head
   [:style
    page-css]])

(defn header
  "Header with query nav"
  [path-prefix
   params]
  (cond-> [:header]
    (not-empty params)
    (conj
     (let [truncator-id (str
                         #?(:clj (java.util.UUID/randomUUID)
                            :cljs (random-uuid)))]
       [:nav.query
        [:input.truncator-toggle
         {:id truncator-id
          :type "checkbox"
          :style "display:none;"}]
        [:label.truncator-toggle-label.query-toggle
         {:for truncator-id}
         "Query"]
        (jr/json->hiccup
         (reduce-kv
          (fn [m k v]
            (assoc m
                   k
                   [v
                    ^::jr/rendered
                    [:a.query-remove-link
                     {:href (statements-link
                             path-prefix
                             (dissoc params k))}
                     "Remove"]]))
          {}
          params)
         :truncate-after 10)]))))

(defn page
  [& hvecs]
  (format "<!DOCTYPE html>\n<html>%s</html>"
          (cs/join "\n"
                   (map
                    (fn [hvec]
                      (#?(:clj html/html
                          :cljs hic/render-html)
                       hvec))
                    hvecs))))

(defn actor-pred
  [path json]
  (and
   (map? json)
   (some #{"mbox" "mbox_sha1sum" "openid" "account"}
         (keys json))
   (or
    (contains? #{"Agent" "Group"}
               (get json "objectType"))
    (= (peek path) "actor"))))

(defn actor-custom
  [path-prefix
   json & json->hiccup-args]
  (conj (apply json->hiccup
               json
               :ignore-custom true
               json->hiccup-args)
        [:div.json.json-map-action.no-truncate
         [:div.json.json-scalar
          [:a {:href
               (statements-link
                path-prefix
                {:agent json})}
           "Filter..."]]]))

(defn verb-pred
  [path json]
  (and
   (map? json)
   (= (peek path) "verb")
   (get json "id")))

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
           {:href
            (statements-link
             path-prefix
             {:verb (get json "id")})}
           "Filter..."]]]))

(defn activity-pred
  [path json]
  (and (map? json)
       ;; Statements are similar so we exempt those
       (not (get json "actor"))
       (get json "id")
       (or (= "Activity" (get json "objectType"))
           (nil? (get json "objectType")))))

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
           {:href
            (statements-link
             path-prefix
             {:activity (get json "id")})}
           "Filter..."]]]))

(defn reg-pred [path _json]
  (= "registration" (peek path)))

(defn reg-custom [path-prefix
                  json & _]
  [:div.json.json-scalar
   [:a
    {:href (statements-link
            path-prefix
            {:registration json})}
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
           {:href
            (statements-link
             path-prefix
             {:statementId (get json "id")})}
           "View..."]]]))

(defn sid-pred
  "Detect a statement id to link"
  [[root-k idx id-k :as path]
   json]
  (or (= path ["id"])
      (and (= [:statements "id"]
              [root-k id-k])
           (number? idx))))

(defn sid-custom
  [path-prefix
   json & _]
  [:div.json.json-scalar
   [:a
    {:href (statements-link
            path-prefix
            {:statementId json})}
    json]])

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
            path-prefix)
   ;; linkable statement id
   sid-pred
   (partial sid-custom
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
  [{path-prefix :com.yetanalytics.lrs.pedestal.interceptor/path-prefix
    {params :xapi.statements.GET.request/params} :xapi
    :or {path-prefix ""
         params {}}
    :as ctx}
   statement]
  (let [statement-rendered
        (json->hiccup
         statement
         :custom (statement-custom path-prefix)
         :key-weights statement-key-weights
         :truncate-after 10
         :truncate-after-min 1
         :truncate-after-mod -9)]
    (if (unwrap? ctx)
      #?(:clj (html/html statement-rendered)
         :cljs (hic/render-html statement-rendered))
      (page
       head
       #_[:head
        [:style
         (slurp (io/resource "lrs/statements/style.css"))]]
       [:body
        (header path-prefix
                params)
        [:main
         statement-rendered]]))))

(defn statement-response
  "Given the ctx and statement, respond with a page"
  [ctx
   statement]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (statement-page
          ctx
          statement)})

(defn statements-page
  [{path-prefix :com.yetanalytics.lrs.pedestal.interceptor/path-prefix
    {params :xapi.statements.GET.request/params} :xapi
    :or {path-prefix ""
         params {}}
    :as ctx}
   {:keys [statements]
    ?more :more}]
  (let [statement-response-rendered
        (json->hiccup
         (cond-> {:statements statements}
           ?more (assoc :more ?more))
         :custom (statement-custom path-prefix)
         :key-weights statement-key-weights
         :truncate-after 2
         :truncate-after-min 1
         :truncate-after-mod -1)]
    (if (unwrap? ctx)
      #?(:clj (html/html statement-response-rendered)
         :cljs (hic/render-html statement-response-rendered))
      (page
       head
       #_[:head
        [:style
         (slurp (io/resource "lrs/statements/style.css"))]]
       [:body
        (header path-prefix
                params)
        [:main
         statement-response-rendered]]))))

(defn statements-response
  "Given the ctx and a statement result obj, respond with a page"
  [ctx
   statement-result]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (statements-page
          ctx
          statement-result)})
