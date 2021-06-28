(ns com.yetanalytics.lrs.pedestal.interceptor.xapi
  (:require [clojure.spec.alpha :as s :include-macros true]
            [xapi-schema.spec.resources :as xsr]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.string :as cstr]
            [io.pedestal.http.body-params :as body-params]
            #?@(:clj [[cheshire.core :as json]
                      [clojure.java.io :as io]
                      [io.pedestal.log :as log]]
                :cljs [[goog.string :refer [format]]
                       [goog.string.format]
                       [com.yetanalytics.lrs.util.log :as log]]))
  #?(:clj (:import [java.io InputStream ByteArrayInputStream])))


(def valid-alt-request-headers
  [:Authorization :X-Experience-API-Version :Content-Type
   :Content-Length :If-Match :If-None-Match
   ;; :Accept-Language
   ;; :Accept
   ;; :Accept-Encoding
   ])

(defn- execute-next [ctx interceptor]
  (update ctx ::chain/queue
          (fn [q]
            (apply conj
                   #?(:clj clojure.lang.PersistentQueue/EMPTY
                      :cljs cljs.core/PersistentQueue.EMPTY)
                   interceptor
                   (seq q)))))

(def alternate-request-syntax-interceptor
  {:name ::alternate-request-syntax-interceptor
   :enter (fn [{:keys [request] :as ctx}]
            ;; If this is a request with a smuggled verb
            (if (some-> request :params :method)
              (if (not= :post (:original-request-method request))
                (assoc (chain/terminate ctx)
                       :response
                       {:status 400
                        :body {:error
                               {:message "xAPI alternate request syntax must use POST!"}}})
                (if-let [extra-params (some-> ctx
                                              :request
                                              :query-params
                                              not-empty)]
                  ;; We can't have extra query params
                  (assoc (chain/terminate ctx)
                         :response
                         {:status 400
                          :body {:error {:message "xAPI Alternate Syntax does not allow extra query params."}}})
                  (let [{:keys [params form-params]} request
                        {:strs [content-type
                                content-length]
                         :as form-headers}
                        (reduce conj {}
                                (map #(update % 0 (comp cstr/lower-case
                                                        name))
                                     (select-keys (:form-params request)
                                                  valid-alt-request-headers)))
                        new-params (apply dissoc form-params
                                          ;; don't let content in
                                          :content
                                          valid-alt-request-headers)
                        {:keys [content]} form-params
                        ?content-type (or content-type
                                          (and content
                                               "application/json"))
                        ?content-bytes
                        #?(:clj (when content
                                  (.getBytes ^String content "UTF-8"))
                           :cljs nil)
                        ?content-length (or content-length
                                            #?(:clj (and ?content-bytes
                                                         (count ?content-bytes))
                                               :cljs (and content
                                                          (.-length content))))]

                    (cond-> (assoc ctx
                                   :request
                                   (-> request
                                       (dissoc :form-params)
                                       (update :headers merge form-headers)
                                       ;; replace params with the other form params
                                       (assoc :params
                                              new-params)
                                       (cond->
                                           ?content-type
                                         (assoc :content-type ?content-type)
                                         ?content-length
                                         (assoc :content-length ?content-length)
                                         ;; If there's content, make it an input stream
                                         #?(:clj ?content-bytes :cljs content)
                                         (assoc :body #?(:clj (ByteArrayInputStream.
                                                               ^bytes ?content-bytes)
                                                         :cljs content)))))
                      content
                      ;; TODO: Need to figure out body-params in cljs
                      #?(:clj (execute-next (body-params/body-params
                                             (body-params/default-parser-map
                                              :json-options {:key-fn str})))
                         :cljs (execute-next (body-params/body-params
                                              (body-params/default-parser-map))))))))
              ctx))})

(defn conform-cheshire [spec-kw x]
  #?(:clj (binding [xsr/*read-json-fn* json/parse-string-strict
                    xsr/*write-json-fn* json/generate-string]
            (s/conform spec-kw x))
     :cljs (s/conform spec-kw x)))


(defn invalid-extra-params [^String path-info
                            request-method
                            params]
  (cond
    (.endsWith path-info "/statements")
    (case request-method
      :get (let [{:keys [statementId
                         voidedStatementId]} params]
             (cond statementId
                   (not-empty (dissoc params
                                      :statementId
                                      :format
                                      :attachments))
                   voidedStatementId
                   (not-empty (dissoc params
                                      :voidedStatementId
                                      :format
                                      :attachments))
                   :else
                   (not-empty (dissoc params
                                      :agent
                                      :verb
                                      :activity
                                      :registration
                                      :related_activities
                                      :related_agents
                                      :since
                                      :until
                                      :limit
                                      :format
                                      :attachments
                                      :ascending
                                      ;; TODO: handle param-based MORE implementations
                                      :page
                                      :from))))
      nil)
    :else nil))

(defn json-str [x]
  #?(:clj (json/generate-string x)
     :cljs (.stringify js/JSON (clj->js x))))

(defn params-interceptor
  "Interceptor factory, given a spec keyword, it validates params against it.
   coerce-params is a map of param to coercion function."
  [spec-kw]
  {:name (let [[k-ns k-name] ((juxt namespace name) spec-kw)]
           (keyword k-ns (str k-name "-interceptor")))
   :enter (fn [{:keys [request] :as ctx}]
            (let [raw-params (or (:params request) {})
                  params (conform-cheshire spec-kw raw-params)
                  {:keys [path-info request-method]} request]
              (if-not (or (= ::s/invalid params)
                          (invalid-extra-params path-info
                                                request-method
                                                params))
                (assoc-in ctx [:xapi spec-kw] params)
                (assoc (chain/terminate ctx)
                       :response
                       {:status 400
                        :headers {"Content-Type" "application/json"}
                        :body (json-str
                               {:error
                                {:message (format "Invalid Params for path: %s" path-info)
                                 :params raw-params}})}))))})
