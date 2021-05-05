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

(def xAPIVersionRegEx
  (let [suf-part "[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*"
        suffix   (str "(\\.[0-9]+(?:-" suf-part ")?(?:\\+" suf-part ")?)?")
        ver-str  (str "^[1-2]\\.0" suffix "$")]
    (re-pattern ver-str)))

(def require-xapi-version-interceptor
  (i/interceptor
   {:name ::require-xapi-version
    :enter (fn [{{:keys [^String path-info]} :request
                 :as ctx}]
             (if-let [version-header (get-in ctx [:request :headers "x-experience-api-version"])]
               (if (re-matches xAPIVersionRegEx
                               version-header)
                 (assoc ctx :com.yetanalytics.lrs/version version-header)
                 (assoc (chain/terminate ctx)
                        :response
                        {:status 400
                         :headers {#?(:cljs "Content-Type"
                                      :clj "content-type")
                                   "application/json"
                                   "x-experience-api-version" "2.0.0"}
                         :body
                         {:error {:message "X-Experience-API-Version header invalid!"}}}))
               (assoc (chain/terminate ctx)
                      :response
                      {:status 400
                       :headers {#?(:cljs "Content-Type"
                                    :clj "content-type") "application/json"
                                 "x-experience-api-version"
                                 ;; TODO: The new tests are broken and require
                                 ;; different things in different contexts
                                 ;; TODO: update when tests are fixed!!!
                                 ;; HACK: special handling for statements-only xapi updates
                                 (cond
                                   (or (.endsWith path-info "/xapi/statements"))
                                   "2.0.0"
                                   :else "1.0.3")}
                       :body
                       {:error {:message "X-Experience-API-Version header required!"}}})))}))

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
             (assoc-in ctx
                       [:response :headers "X-Experience-API-Version"]
                       ;; Should be latest patch version
                       "2.0.0"))}))

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
#_(def require-and-set-xapi-version-interceptor
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
                 :or {file-path nil
                      #?@(:clj [request-logger http/log-request])
                      router :map-tree
                      resource-path nil
                      not-found-interceptor http/not-found
                      method-param-name :_method
                      ext-mime-types {}
                      enable-session nil
                      enable-csrf nil
                      secure-headers {}}} service-map
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
                     ;; TODO: remove these debugs
                     (cond-> [#?@(:cljs [body-string-interceptor
                                         #_ppman])
                              ]
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

(def body-params-interceptor
  #?(:clj (body-params/body-params
           (body-params/default-parser-map
            :json-options {:key-fn str}))
     :cljs (body-params/body-params)))

(def json-body-interceptor
  http/json-body)

(def alternate-request-syntax-interceptor
  xapi/alternate-request-syntax-interceptor)

(def common-interceptors [x-forwarded-for-interceptor
                          json-body-interceptor
                          error-interceptor
                          body-params-interceptor
                          alternate-request-syntax-interceptor
                          set-xapi-version-interceptor
                          xapi-ltags-interceptor])

(def doc-interceptors-base
  [x-forwarded-for-interceptor
   require-xapi-version-interceptor
   alternate-request-syntax-interceptor
   set-xapi-version-interceptor
   xapi-ltags-interceptor])
