(ns com.yetanalytics.lrs.pedestal.interceptor
  "xAPI Route Interceptors"
  (:require [clojure.string :as cstr]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http :as http]
            [io.pedestal.http.cors :as cors]
            #?@(:clj [[io.pedestal.http.csrf :as csrf]
                      [io.pedestal.http.secure-headers :as sec-headers]
                      [io.pedestal.log :as log]])
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi]
            [com.yetanalytics.lrs.pedestal.http.multipart-mixed :as multipart-mixed]
            [com.yetanalytics.lrs.util.hash :refer [sha-1]]
            [com.yetanalytics.lrs.spec.common :as cs]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            #?@(:cljs [[cljs.nodejs :as node]
                       [cljs.pprint :refer [pprint]]
                       [concat-stream]
                       [com.yetanalytics.lrs.util.log :as log]])))

;; Enter
(defn lrs-interceptor
  "An interceptor that takes an lrs implementation and puts it in the context"
  [lrs]
  (i/interceptor
   {:name ::lrs
    :enter (fn [ctx]
             (assoc ctx :com.yetanalytics/lrs lrs))}))

(def require-xapi-version-interceptor
  (i/interceptor
   {:name ::require-xapi-version
    :enter (fn [ctx]
             ;; if this is an html request, don't require this
             ;; browsers can't provide it
             (if (si/accept-html? ctx)
               (assoc-in ctx
                         [:request :headers "x-experience-api-version"]
                         "1.0.3")
               (if-let [version-header (get-in ctx [:request :headers "x-experience-api-version"])]
                 (if (#{"1.0"   ;; Per spec, if we accept 1.0.0,
                        ;; 1.0 must be accepted as if 1.0.0
                        "1.0.0"
                        "1.0.1"
                        "1.0.2"
                        "1.0.3"} version-header)
                   ctx
                   (assoc (chain/terminate ctx)
                          :response
                          #?(:cljs {:status 400
                                    :headers {"Content-Type" "application/json"}
                                    :body
                                    {:error {:message "X-Experience-API-Version header invalid!"}}}
                             ;; TODO: Figure this out and dix
                             ;; this is odd. For some reason, the conformance
                             ;; tests intermittently fail when this error response
                             ;; comes in w/o content-length. So we string it out
                             ;; and set it. Who knows.
                             :clj
                             {:status 400
                              :headers {"Content-Type" "application/json"
                                        "Content-Length" "66"}
                              :body "{\"error\": {\"message\": \"X-Experience-API-Version header invalid!\"}}"})))
                 (assoc (chain/terminate ctx)
                        :response
                        {:status 400
                         :headers {"Content-Type" "application/json"}
                         :body
                         {:error {:message "X-Experience-API-Version header required!"}}}))))}))

(def x-forwarded-for-interceptor
  (i/interceptor
   {:name ::x-forwarded-for
    :enter (fn [{:keys [request] :as ctx}]
             (if-let [xff (get-in request [:headers "x-forwarded-for"])]
               (update ctx :request assoc :remote-addr (last (cstr/split xff #"\s*,\s*")))
               ctx))}))

(defn xapi-attachments-interceptor
  "Interceptor factory that takes a multipart parsing function, and adds xapi
   attachments to the request, if it is multipart/mixed;"
  [parse-request]
  (i/interceptor
   {:name ::xapi-attachments
    :enter (fn [{:keys [request] :as ctx}]
             (if-let [attachments (parse-request request)]
               (assoc ctx :xapi/attachments attachments)
               ctx))}))

(def valid-alt-request-headers
  [:Authorization :X-Experience-API-Version :Content-Type
   :Content-Length :If-Match :If-None-Match
   ;; :Accept-Language
   ;; :Accept
   ;; :Accept-Encoding
   ])

#_(def xapi-alternate-request-interceptor
  (i/interceptor
   {:name ::xapi-alternate-request-headers
    :enter (fn [{:keys [request] :as ctx}]
             (if (some-> request :params :method)
               (let [request (body-params/form-parser request)
                     keyword-or-string-headers (into valid-alt-request-headers
                                                     (map keyword
                                                          valid-alt-request-headers))
                     form-headers  (reduce conj {}
                                           (map #(update % 0 (comp cstr/lower-case
                                                                   name))
                                                (select-keys (:form-params request)
                                                             ;; for some reason, they are sometimes keywords
                                                             keyword-or-string-headers)))]
                 (assoc ctx :request (apply dissoc
                                            (update request :headers merge form-headers)
                                            :params
                                            keyword-or-string-headers)))
               ctx))}))

(defn parse-accept-language
  "Parse an Accept-Language header and return a vector in order of quality"
  [^String header]
  (into []
        (map first)
        (sort-by second >
                 (map (comp
                       (fn [part-str]
                         (let [[ltag ?q] (cstr/split part-str #";")]
                           [ltag (if ?q
                                   (try (#?(:clj java.lang.Double/parseDouble
                                            :cljs js/parseFloat) (second (cstr/split ?q #"=")))
                                        (catch #?(:clj java.lang.NumberFormatException
                                                  :cljs js/Error) _
                                           (throw (ex-info
                                                   "Invalid Accept-Language header"
                                                   {:type ::invalid-accept-language-header
                                                    :header header}))))
                                   1.0)]))
                       cstr/trim)
                      (cstr/split header #",")))))

(def xapi-ltags-interceptor
  "Parse the accept-language header and add it to the context"
  (i/interceptor
   {:name ::xapi-ltags
    :enter (fn [ctx]
             (if-let [accept-language (get-in ctx [:request :headers "accept-language"])]
               (try
                 (assoc ctx :xapi/ltags (parse-accept-language accept-language))
                 (catch clojure.lang.ExceptionInfo exi
                   (let [exd (ex-data exi)]
                     (if (= (:type exd) ::invalid-accept-language-header)
                       (assoc (chain/terminate ctx)
                              :response {:status 400
                                         :body
                                         {:message (.getMessage exi)
                                          :header accept-language}})
                       (throw exi)))))
               ctx))}))

;; Leave
(def set-xapi-version-interceptor
  (i/interceptor
   {:name ::set-xapi-version
    :leave (fn [ctx]
             (assoc-in ctx [:response :headers "X-Experience-API-Version"]
                       "1.0.3"))}))

(defn calculate-etag [x]
  (sha-1 x))

;; TODO: handle weak etags
(def etag-string-pattern
  #"\w+")

(defn etag-header->etag-set
  [etag-header]
  (into #{} (re-seq etag-string-pattern etag-header)))

(defn- quote-etag [etag]
  (str "\"" etag "\""))

(defn etag-leave
  [{:keys [request response] :as ctx}]
  (let [etag (or
              (::etag ctx)
              (get-in response [:headers "etag"])
              (get-in response [:headers "Etag"])
              (get-in response [:headers "ETag"])
              (some-> response :body meta :etag)
              (calculate-etag (:body response)))]
    (-> ctx
        (assoc ::etag etag)
        (update-in [:response :headers] dissoc "etag" "ETag" "Etag")
        (update-in [:response :headers] merge {"ETag" (quote-etag etag)}))))

;; Combo
(def require-and-set-xapi-version-interceptor
  (i/interceptor
   (merge
    require-xapi-version-interceptor
    set-xapi-version-interceptor
    {:name ::require-and-set-xapi-version})))

;; TODO: Port the rest of the interceptors


(def xapi-method-param
  (i/interceptor
   {:name ::xapi-method-param
    :enter (fn [ctx]
             (if-let [method (get-in ctx [:request :query-params :method])]
               (-> ctx
                   (assoc-in [:request :query-params :method]
                             (cstr/lower-case method))
                   (assoc-in [:request :original-request-method]
                             (get-in ctx [:request :request-method])))
               ctx))}))

;; Gracefully handle uncaught errors, deferring to custom responses.

(defn- exi?
  "Is it an ExceptionInfo?"
  [x]
  (instance? #?(:clj clojure.lang.ExceptionInfo
                :cljs ExceptionInfo) x))

(defn- error?
  "otherwise, is it sufficiently exceptional?"
  [x]
  (instance? #?(:clj Exception
                :cljs js/Error) x))

(def error-interceptor
  (i/interceptor
   {:name ::error-interceptor
    :error (fn [{:keys [response]
                 :as ctx} ex]
             (if response
               ;; defer to custom upstream response
               ctx
               (do
                 ;; Log all unhandled/bubbled errors
                 (log/error :msg "Unhandled LRS Error"
                            :exception ex)
                 (assoc ctx
                      :response
                      {:status 500
                       :body
                       {:error
                        {:type (cond (nil? ex)
                                     {:name "unknown"}
                                     (exi? ex)
                                     (let [{:keys [type exception-type]
                                            :as exd} (ex-data ex)
                                           type-k (or exception-type
                                                      type
                                                      :unknown/unknown)
                                           [tns tname] ((juxt namespace name) type-k)]
                                       (merge (when tns {:ns tns})
                                              {:name tname}))
                                     (error? ex)
                                     {:name (str (type ex))}
                                     (string? ex)
                                     {:name ex}
                                     :else
                                     {:name "unknown"})}}}))))}))

;; Time Requests

(def request-timer
  (i/interceptor
   {:name ::request-timer
    :enter (fn [ctx]
             (assoc ctx ::request-enter-ms #?(:clj (System/currentTimeMillis)
                                              :cljs (.getTime (js/Date.)))))
    :leave (fn [ctx]
             (let [request-ms (- #?(:clj (System/currentTimeMillis)
                                    :cljs (.getTime (js/Date.)))
                                 (::request-enter-ms ctx))
                   {:keys [path-info request-method]} (:request ctx)]
               #?@(:clj [(log/histogram "lrs request-ms all"
                                        request-ms)
                         (log/histogram (format "lrs request-ms %s %s"
                                                path-info
                                                request-method)
                                        request-ms)])
               ctx))}))

;; Combined interceptors

#?(:cljs (def ppman
           (i/interceptor
            {:name ::pp
             :enter (fn [ctx]
                      (pprint ctx)
                      ctx)
             :leave (fn [ctx]
                      (pprint ctx)
                      ctx)})))

#?(:cljs (def body-string-interceptor
           (i/interceptor
            {:name ::body-string
             :enter (fn [{{body :body} :request :as ctx}]
                      (if (string? body)
                        ctx
                        (let [ctx-chan (a/promise-chan)]
                          (.pipe body
                                 (concat-stream. (fn [body-buffer]
                                                   (a/go (a/>! ctx-chan
                                                               (assoc-in ctx
                                                                         [:request :body]
                                                                         (.toString body-buffer)))))))
                          ctx-chan)))})))

(defn path-prefix-interceptor
  "Inject xapi path prefix for use in other interceptors"
  [path-prefix]
  (i/interceptor
   {:name ::path-prefix
    :enter #(assoc % ::path-prefix path-prefix)}))

(def enable-statement-html-interceptor
  (i/interceptor
   {:name ::enable-statement-html
    :enter #(assoc % ::statement-html? true)}))

(defn xapi-default-interceptors
          "Like io.pedestal.http/default-interceptors, but includes support for xapi alt
   request syntax, etc."
          [service-map]
          (let [{interceptors ::http/interceptors
                 request-logger ::http/request-logger
                 routes ::http/routes
                 router ::http/router
                 file-path ::http/file-path
                 resource-path ::http/resource-path
                 method-param-name ::http/method-param-name
                 allowed-origins ::http/allowed-origins
                 not-found-interceptor ::http/not-found-interceptor
                 ext-mime-types ::http/mime-types
                 enable-session ::http/enable-session
                 enable-csrf ::http/enable-csrf
                 secure-headers ::http/secure-headers
                 server-type ::http/type
                 path-prefix ::path-prefix
                 statement-html? ::enable-statement-html
                 :or {file-path nil
                      #?@(:clj [request-logger http/log-request])
                      router :map-tree
                      resource-path nil
                      not-found-interceptor http/not-found
                      method-param-name :_method
                      ext-mime-types {}
                      enable-session nil
                      enable-csrf nil
                      secure-headers {}
                      path-prefix "/xapi"
                      statement-html? true}} service-map
                processed-routes (cond
                                   (satisfies? route/ExpandableRoutes routes) (route/expand-routes routes)
                                   (fn? routes) routes
                                   (nil? routes) nil
                                   (and (seq? routes) (every? map? routes)) routes
                                   :else (throw (ex-info "Routes specified in the service map don't fulfill the contract.
                                                 They must be a seq of full-route maps or satisfy the ExpandableRoutes protocol"
                                                         {:routes routes})))]
            (if-not interceptors
              (assoc service-map ::http/interceptors
                     (cond-> [(path-prefix-interceptor
                               path-prefix)
                              ;; Fix for cljs string body TODO: evaluate
                              #?@(:cljs [body-string-interceptor])
                              ]
                       statement-html? (conj enable-statement-html-interceptor)
                       ;; For Jetty, ensure that request bodies are drained.
                       ;; (= server-type :jetty) (conj util/ensure-body-drained)
                       (some? request-logger) (conj (io.pedestal.interceptor/interceptor request-logger))
                       (some? allowed-origins) (conj (cors/allow-origin allowed-origins))
                       (some? not-found-interceptor) (conj (io.pedestal.interceptor/interceptor not-found-interceptor))
                       true (conj (middlewares/content-type {:mime-types ext-mime-types}))
                       true (conj route/query-params)
                       true (conj xapi-method-param)
                       true (conj (route/method-param :method))
                       ;; The etag interceptor may mess with routes, so it's important not to have any
                       ;; important leave stuff after it in the defaults
                       ;; TODO: If all platforms support async/NIO responses, we can bring this back
                                        ;(not (nil? resource-path)) (conj (middlewares/fast-resource resource-path))
                       #?@(:clj [(some? resource-path) (conj (middlewares/resource resource-path))
                                 (some? file-path) (conj (middlewares/file file-path))])
                       true (conj (#?(:clj route/router
                                      :cljs route/delegate-router) processed-routes router))))
              service-map)))

(def common-interceptors [x-forwarded-for-interceptor
                          http/json-body
                          error-interceptor
                          #?(:clj (body-params/body-params
                                   (body-params/default-parser-map
                                    :json-options {:key-fn str}))
                             :cljs (body-params/body-params))
                          xapi/alternate-request-syntax-interceptor
                          set-xapi-version-interceptor
                          xapi-ltags-interceptor])

(def doc-interceptors-base
  [x-forwarded-for-interceptor
   xapi/alternate-request-syntax-interceptor
   set-xapi-version-interceptor
   xapi-ltags-interceptor])

(def xapi-protected-interceptors
  [require-xapi-version-interceptor])
