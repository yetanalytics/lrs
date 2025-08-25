(ns com.yetanalytics.lrs.pedestal.interceptor.xapi
  (:require [clojure.spec.alpha :as s :include-macros true]
            [clojure.string :as cstr]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.body-params :as body-params]
            [xapi-schema.spec :as xs :include-macros true]
            #?@(:clj [[cheshire.core :as json]
                      [xapi-schema.spec.resources :as xsr]]
                :cljs [[goog.string :refer [format]]
                       [goog.string.format]]))
  #?(:clj (:import [java.io InputStream ByteArrayInputStream])))

(defn error!
  "Return a 400 with the given message"
  [ctx message]
  (assoc (chain/terminate ctx)
         :response
         {:status 400
          :headers {#?(:cljs "Content-Type"
                       :clj "content-type") "application/json"
                    ;; TODO: dispatch on type in ctx
                    "x-experience-api-version"
                    (:com.yetanalytics.lrs/spec-version
                     ctx
                     "2.0.0")}
          :body
          {:error {:message message}}}))

(def valid-alt-request-headers
  [:Authorization
   :X-Experience-API-Version
   :Content-Type
   :Content-Length
   :If-Match
   :If-None-Match
   ;; :Accept-Language
   ;; :Accept
   ;; :Accept-Encoding
   ])

(defn- execute-next [ctx interceptor]
  (update ctx
          ::chain/queue
          (fn queue->persistent-queue [q]
            (let [pq #?(:clj clojure.lang.PersistentQueue/EMPTY
                        :cljs cljs.core/PersistentQueue.EMPTY)]
              (apply conj pq interceptor (seq q))))))

(def alternate-request-syntax-interceptor
  {:name ::alternate-request-syntax-interceptor
   :enter (fn [{:keys [request] :as ctx}]
            ;; If this is a request with a smuggled verb
            ;; and version is pre-2.0.x
            (if (some-> request :params :method)
              (let [version (:com.yetanalytics.lrs/version ctx)]
                ;; Version 2.0.0 and up do not allow this so we error
                (if (or (nil? version)
                        (not (.startsWith ^String version "1")))
                    (error!
                     ctx
                     "xAPI alternate request syntax not supported!")
                  (if (not= :post (:original-request-method request))
                    (assoc (chain/terminate ctx)
                           :response
                           {:status 400
                            :body
                            {:error
                             {:message "xAPI alternate request syntax must use POST!"}}})
                    (if (some-> ctx :request :query-params not-empty)
                      ;; We can't have extra query params
                      (assoc (chain/terminate ctx)
                             :response
                             {:status 400
                              :body
                              {:error
                               {:message "xAPI Alternate Syntax does not allow extra query params."}}})
                      (let [;; Destructuring
                            {:keys [form-params]} request
                            {:keys [content]}     form-params
                            {:strs [content-type
                                    content-length]
                             :as form-headers}
                            (->> valid-alt-request-headers
                                 (select-keys (:form-params request))
                                 (map #(update % 0 (comp cstr/lower-case name)))
                                 (reduce conj {}))
                            ;; Content + Params
                            new-params     (apply dissoc
                                                  form-params
                                                  :content ; don't let content in
                                                  valid-alt-request-headers)
                            ?content-type  (or content-type
                                               (and content
                                                    "application/json"))
                            ?content-bytes #?(:clj (when content
                                                     (.getBytes ^String content "UTF-8"))
                                              :cljs nil)
                            ?content-length (or content-length
                                                #?(:clj (and ?content-bytes
                                                             (count ?content-bytes))
                                                   :cljs (and content
                                                              (.-length content))))
                            ;; Dummy binding to avoid clj-kondo warning, since
                            ;; ?content-bytes is only used in clj
                            _ ?content-bytes
                            ;; Corece request
                            request'  (-> request
                                          (dissoc :form-params)
                                          (update :headers merge form-headers)
                                          ;; replace params with the other form params
                                          (assoc :params new-params))
                            request'' (cond-> request'
                                        ?content-type
                                        (assoc :content-type ?content-type)
                                        ?content-length
                                        (assoc :content-length ?content-length)
                                        ;; If there's content, make it an input stream
                                        #?(:clj ?content-bytes :cljs content)
                                        (assoc :body #?(:clj (ByteArrayInputStream.
                                                              ^bytes ?content-bytes)
                                                        :cljs content)))
                            ;; TODO: Need to figure out body-params in cljs
                            parser-m #?(:clj (body-params/default-parser-map
                                              :json-options {:key-fn str})
                                        :cljs (body-params/default-parser-map))]
                        (cond-> (assoc ctx :request request'')
                          content
                          (execute-next (body-params/body-params parser-m))))))))
              ctx))})

(defn conform-cheshire [spec-kw x]
  #?(:clj (binding [xsr/*read-json-fn* json/parse-string-strict
                    xsr/*write-json-fn* json/generate-string]
            (s/conform spec-kw x))
     :cljs (s/conform spec-kw x)))


(defn invalid-extra-params [^String path-info
                            request-method
                            params]
  (when (.endsWith path-info "/statements")
    (when (= :get request-method)
      (let [{:keys [statementId
                    voidedStatementId]} params]
        (cond
          statementId
          (not-empty (dissoc params
                             :statementId
                             :format
                             :attachments
                             :unwrap_html))
          voidedStatementId
          (not-empty (dissoc params
                             :voidedStatementId
                             :format
                             :attachments
                             :unwrap_html))
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
                             :from
                             :unwrap_html)))))))

(defn json-str [x]
  #?(:clj (json/generate-string x)
     :cljs (.stringify js/JSON (clj->js x))))

(defn params-interceptor
  "Interceptor factory, given a spec keyword, it validates params against it.
   coerce-params is a map of param to coercion function."
  [spec-kw]
  {:name
   (let [[k-ns k-name] ((juxt namespace name) spec-kw)]
     (keyword k-ns (str k-name "-interceptor")))
   :enter
   (fn params-interceptor-fn
     [{:keys [request] :as ctx}]
     (let [raw-params (or (:params request) {})
           params
           #?(:clj (conform-cheshire spec-kw raw-params)
              ;; Force binding of spec version in cljs
              :cljs
              (let [v (:com.yetanalytics.lrs/spec-version ctx)]
                (binding [xs/*xapi-version* (or v "2.0.0")]
                  (conform-cheshire spec-kw raw-params))))
           {:keys [path-info
                   request-method]} request]
       (if-not (or (= ::s/invalid params)
                   (invalid-extra-params path-info
                                         request-method
                                         params))
         (assoc-in ctx [:xapi spec-kw] params)
         (assoc (chain/terminate ctx)
                :response
                {:status  400
                 :headers {"Content-Type" "application/json"}
                 :body
                 (json-str
                  {:error
                   {:message (format "Invalid Params for path: %s" path-info)
                    :params raw-params}})}))))})
