(ns com.yetanalytics.lrs.pedestal.interceptor.xapi
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.body-params :as body-params]
            [cheshire.core :as json]
            [clojure.string :as cstr]
            [clojure.java.io :as io])
  (:import [java.io InputStream ByteArrayInputStream]))


(def valid-alt-request-headers
  [:Authorization :X-Experience-API-Version :Content-Type
   :Content-Length :If-Match :If-None-Match
   ;; :Accept-Language
   ;; :Accept
   ;; :Accept-Encoding
   ])

(defn- execute-next [ctx interceptor]
  (update ctx :io.pedestal.interceptor.chain/queue
          (fn [q]
            (apply conj
                   clojure.lang.PersistentQueue/EMPTY
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
                          :body {:error {:message "xAPI Alternate Syntax does not allow extra params."}}})
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
                        ?content-bytes (when content
                                         (.getBytes ^String content "UTF-8"))
                        ?content-length (or content-length
                                            (and ?content-bytes
                                                 (count ?content-bytes)))]

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
                                         ?content-bytes
                                         (assoc :body (ByteArrayInputStream.
                                                       ^bytes ?content-bytes)))))
                      content (execute-next (body-params/body-params
                                             (body-params/default-parser-map
                                              :json-options {:key-fn str})))))))
              ctx))})

(defn conform-cheshire [spec-kw x]
  (binding [xsr/*read-json-fn* json/parse-string-strict
            xsr/*write-json-fn* json/generate-string]
    (s/conform spec-kw x)))


(defn invalid-extra-params? [^String path-info
                             request-method
                             params]
  (cond
    (.endsWith path-info "/xapi/statements")
    (case request-method
      :get (let [{:keys [statementId
                         voidedStatementId]} params]
             (or (when statementId
                   (not-empty (dissoc params
                                      :statementId
                                      :format
                                      :attachments)))
                 (when voidedStatementId
                   (not-empty (dissoc params
                                      :voidedStatementId
                                      :format
                                      :attachments)))))
      false)
    :else false))

(defn params-interceptor
  "Interceptor factory, given a spec keyword, it validates params against it.
   coerce-params is a map of param to coercion function."
  [spec-kw]
  {:name (let [[k-ns k-name] ((juxt namespace name) spec-kw)]
           (keyword k-ns (str k-name "-interceptor")))
   :enter (fn [ctx]
            (let [raw-params (get-in ctx [:request :params] {})
                  params (conform-cheshire spec-kw raw-params)]
              (if-not (or (= ::s/invalid params)
                          (invalid-extra-params? (get-in ctx [:request :path-info])
                                                 (get-in ctx [:request :request-method])
                                                 params))
                (assoc-in ctx [:xapi spec-kw] params)
                (assoc (chain/terminate ctx)
                       :response
                       {:status 400
                        :body {:error
                               {:message "Invalid Params"
                                :spec-error (s/explain-str spec-kw raw-params)}}}))))})
