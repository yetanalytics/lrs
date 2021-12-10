(ns com.yetanalytics.lrs.pedestal.interceptor
  "xAPI Route Interceptors"
  (:require [clojure.string :as cstr]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http :as http]
            [io.pedestal.http.cors :as cors]
            #?@(:clj [[io.pedestal.log :as log]])
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi]
            [com.yetanalytics.lrs.util.hash :refer [sha-1]]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            #?@(:cljs [[cljs.nodejs] ; special require for cljs compliation
                       [clojure.core.async :as a :include-macros true]
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
    :enter
    (fn require-xapi-version [ctx]
      ;; if this is an html request, don't require this
      ;; browsers can't provide it
      (if (si/accept-html? ctx)
        (assoc-in ctx
                  [:request :headers "x-experience-api-version"]
                  "1.0.3")
        (if-let [version-header
                 (get-in ctx [:request :headers "x-experience-api-version"])]
          ;; Per spec, if we accept 1.0.0,
          ;; 1.0 must be accepted as if 1.0.0
          (if (#{"1.0" 
                 "1.0.0"
                 "1.0.1"
                 "1.0.2"
                 "1.0.3"} version-header)
            ;; Version ok
            ctx
            ;; Version not ok
            (assoc
             (chain/terminate ctx)
             :response
             #?(:cljs
                {:status  400
                 :headers {"Content-Type" "application/json"}
                 :body
                 {:error {:message "X-Experience-API-Version header invalid!"}}}
                ;; TODO: Figure this out and dix
                ;; this is odd. For some reason, the conformance
                ;; tests intermittently fail when this error response
                ;; comes in w/o content-length. So we string it out
                ;; and set it. Who knows.
                :clj
                {:status  400
                 :headers {"Content-Type" "application/json"
                           "Content-Length" "66"}
                 :body    "{\"error\": {\"message\": \"X-Experience-API-Version header invalid!\"}}"})))
          (assoc
           (chain/terminate ctx)
           :response
           {:status  400
            :headers {"Content-Type" "application/json"}
            :body
            {:error
             {:message "X-Experience-API-Version header required!"}}}))))}))

(def x-forwarded-for-interceptor
  (i/interceptor
   {:name  ::x-forwarded-for
    :enter (fn x-forwarded-for [{:keys [request] :as ctx}]
             (if-let [xff (get-in request [:headers "x-forwarded-for"])]
               (update ctx
                       :request
                       assoc
                       :remote-addr
                       (last (cstr/split xff #"\s*,\s*")))
               ctx))}))

(defn xapi-attachments-interceptor
  "Interceptor factory that takes a multipart parsing function, and adds xapi
   attachments to the request, if it is multipart/mixed;"
  [parse-request]
  (i/interceptor
   {:name  ::xapi-attachments
    :enter (fn xapi-attachments [{:keys [request] :as ctx}]
             (if-let [attachments (parse-request request)]
               (assoc ctx :xapi/attachments attachments)
               ctx))}))

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

(defn- accept-lang-part->pair
  [header part-str]
  (let [parse-float #?(:clj #(java.lang.Double/parseDouble %)
                       :cljs js/parseFloat)
        [ltag ?q]   (cstr/split part-str #";")]
    [ltag (if ?q
            (try (-> ?q (cstr/split #"=") second parse-float)
                 (catch #?(:clj java.lang.NumberFormatException
                           :cljs js/Error) _
                   (throw (ex-info
                           "Invalid Accept-Language header"
                           {:type   ::invalid-accept-language-header
                            :header header}))))
            1.0)]))

(defn parse-accept-language
  "Parse an Accept-Language header and return a vector in order of quality"
  [^String header]
  (->> (cstr/split header #",")
       (map (comp (partial accept-lang-part->pair header) cstr/trim))
       (sort-by second >)
       (into [] (map first))))

(def xapi-ltags-interceptor
  "Parse the accept-language header and add it to the context"
  (i/interceptor
   {:name ::xapi-ltags
    :enter
    (fn xapi-ltags [ctx]
      (if-let [accept-language
               (get-in ctx [:request :headers "accept-language"])]
        (try
          (assoc ctx
                 :xapi/ltags
                 (parse-accept-language accept-language))
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo) exi
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
   {:name  ::set-xapi-version
    :leave (fn set-xapi-version [ctx]
             (assoc-in ctx
                       [:response :headers "X-Experience-API-Version"]
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
  [{:keys [response] :as ctx}]
  (let [etag (or (::etag ctx)
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
   {:name  ::xapi-method-param
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
   {:name  ::error-interceptor
    :error
    (fn error-fn [{:keys [response] :as ctx} ex]
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
                  :body {:error
                         (merge
                          {:message "Unhandled LRS Error"}
                          (cond
                            (nil? ex)    {:type "unknown"}
                            (exi? ex)    (let [{:keys [type exception-type]} (ex-data ex)
                                               type-k (or exception-type
                                                          type
                                                          :unknown/unknown)
                                               [tns tname] ((juxt namespace name) type-k)]
                                           (merge (when tns {:ns tns})
                                                  {:type tname}))
                            (error? ex)  {:type (str (type ex))}
                            (string? ex) {:type ex}
                            :else        {:type "unknown"}))}}))))}))

;; Time Requests

(def request-timer
  (i/interceptor
   {:name ::request-timer
    :enter (fn request-timer-enter-fn [ctx]
             (assoc ctx ::request-enter-ms #?(:clj (System/currentTimeMillis)
                                              :cljs (.getTime (js/Date.)))))
    :leave (fn request-timer-leave-fn [ctx]
             #?(:clj ; TODO: cljs with `(.getTime (js/Date.))`
                (let [request-ms (- (System/currentTimeMillis)
                                    (::request-enter-ms ctx))
                      {:keys [path-info request-method]} (:request ctx)]
                  (log/histogram "lrs request-ms all"
                                 request-ms)
                  (log/histogram (format "lrs request-ms %s %s"
                                         path-info
                                         request-method)
                                 request-ms)))
             ctx)}))

;; Combined interceptors

#?(:cljs
   (def ppman
     (i/interceptor
      {:name ::pp
       :enter (fn ppman-enter-fn [ctx]
                (pprint ctx)
                ctx)
       :leave (fn ppman-leave-fn [ctx]
                (pprint ctx)
                ctx)})))

#?(:cljs
   (def body-string-interceptor
     (i/interceptor
      {:name ::body-string
       :enter
       (fn body-string [{{body :body} :request :as ctx}]
         (if (string? body)
           ctx
           (let [ctx-chan (a/promise-chan)]
             (.pipe body
                    (concat-stream.
                     (fn [body-buffer]
                       (let [ctx' (assoc-in ctx
                                            [:request :body]
                                            (.toString body-buffer))]
                         (a/go (a/>! ctx-chan ctx'))))))
             ctx-chan)))})))

(defn path-prefix-interceptor
  "Inject xapi path prefix for use in other interceptors"
  [path-prefix]
  (i/interceptor
   {:name  ::path-prefix
    :enter (fn path-prefix-fn [ctx] (assoc ctx ::path-prefix path-prefix))}))

(def enable-statement-html-interceptor
  (i/interceptor
   {:name  ::enable-statement-html
    :enter (fn enable-stmt-html-fn [ctx] (assoc ctx ::statement-html? true))}))

(defn www-auth-realm-interceptor
  [realm]
  (i/interceptor
   {:name  ::www-auth-realm
    :enter (fn www-auth-realm-fn [ctx] (assoc ctx ::www-auth-realm realm))}))

#_{:clj-kondo/ignore [:unused-binding]} ; Shut up VSCode warnings
(defn xapi-default-interceptors
  "Like io.pedestal.http/default-interceptors, but includes support for xapi alt
   request syntax, etc."
  [service-map]
  (let [{interceptors          ::http/interceptors
         request-logger        ::http/request-logger
         routes                ::http/routes
         router                ::http/router
         allowed-origins       ::http/allowed-origins
         not-found-interceptor ::http/not-found-interceptor
         ext-mime-types        ::http/mime-types
         ;; LRS Specific:
         path-prefix     ::path-prefix
         statement-html? ::enable-statement-html
         www-auth-realm  ::www-auth-realm
         ;; Currently unused
         file-path         ::http/file-path
         resource-path     ::http/resource-path
         method-param-name ::http/method-param-name
         enable-session    ::http/enable-session
         enable-csrf       ::http/enable-csrf
         secure-headers    ::http/secure-headers
         server-type       ::http/type
         :or {#?@(:clj [request-logger http/log-request])
              router                :map-tree
              not-found-interceptor http/not-found
              ext-mime-types        {}
              path-prefix           "/xapi"
              statement-html?       true
              www-auth-realm        "LRS"
              ;; Currently unused
              file-path             nil
              resource-path         nil
              method-param-name     :_method
              enable-session        nil
              enable-csrf           nil
              secure-headers        {}}}
        service-map
        processed-routes
        (cond
          (satisfies? route/ExpandableRoutes routes) (route/expand-routes routes)
          (fn? routes) routes
          (nil? routes) nil
          (and (seq? routes) (every? map? routes)) routes
          :else (throw
                 (ex-info "Routes specified in the service map don't fulfill the contract.
                           They must be a seq of full-route maps or satisfy the ExpandableRoutes protocol"
                          {:routes routes})))]
    (if-not interceptors
      (assoc
       service-map
       ::http/interceptors
       (cond-> [(path-prefix-interceptor path-prefix)
                (www-auth-realm-interceptor www-auth-realm)
                #?@(:cljs [body-string-interceptor])] ; Fix for cljs string body TODO: evaluate
         statement-html? (conj enable-statement-html-interceptor)
         ;; For Jetty, ensure that request bodies are drained.
         ;; (= server-type :jetty) (conj util/ensure-body-drained)
         (some? request-logger)        (conj (io.pedestal.interceptor/interceptor
                                              request-logger))
         (some? allowed-origins)       (conj (cors/allow-origin
                                              allowed-origins))
         (some? not-found-interceptor) (conj (io.pedestal.interceptor/interceptor
                                              not-found-interceptor))
         true (conj (middlewares/content-type {:mime-types ext-mime-types}))
         true (conj route/query-params)
         true (conj xapi-method-param)
         true (conj (route/method-param :method))
         ;; The etag interceptor may mess with routes, so it's important not to have any
         ;; important leave stuff after it in the defaults
         ;; TODO: If all platforms support async/NIO responses, we can bring this back
         
         ;; (not (nil? resource-path)) (conj (middlewares/fast-resource resource-path))
         
         #?@(:clj
             [(some? resource-path) (conj (middlewares/resource resource-path))
              (some? file-path) (conj (middlewares/file file-path))])
         true (conj (#?(:clj route/router :cljs route/delegate-router)
                     processed-routes router))))
      service-map)))

(def common-interceptors
  (let [body-params
        #?(:clj (body-params/body-params
                 (body-params/default-parser-map
                   :json-options {:key-fn str}))
           :cljs (body-params/body-params))]
    [x-forwarded-for-interceptor
     http/json-body
     body-params
     xapi/alternate-request-syntax-interceptor
     set-xapi-version-interceptor
     xapi-ltags-interceptor]))

(def doc-interceptors-base
  [x-forwarded-for-interceptor
   xapi/alternate-request-syntax-interceptor
   set-xapi-version-interceptor
   xapi-ltags-interceptor])

(def xapi-protected-interceptors
  [require-xapi-version-interceptor])
