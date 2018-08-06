(ns com.yetanalytics.lrs.pedestal.interceptor
  "xAPI Route Interceptors"
  (:require [clojure.string :as cstr]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.params :refer [keyword-params
                                             keyword-body-params
                                             ]]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as i]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.secure-headers :as sec-headers]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi :as xapi]
            [com.yetanalytics.lrs.pedestal.http.multipart-mixed :as multipart-mixed]
            [com.yetanalytics.lrs.spec.common :as cs]
            [clojure.core.async :as a]
            [io.pedestal.log :as log]
            [com.yetanalytics.lrs.pedestal.interceptor.util :as util])
  (:import [java.security MessageDigest]
           [java.io File InputStream]
           [java.nio ByteBuffer]))

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
                        {:status 400
                         :body
                         {:error {:message "X-Experience-API-Version header invalid!"}}}))
               (assoc (chain/terminate ctx)
                      :response
                      {:status 400
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

(def valid-alt-request-headers
  [:Authorization :X-Experience-API-Version :Content-Type
   :Content-Length :If-Match :If-None-Match
   ;; :Accept-Language
   ;; :Accept
   ;; :Accept-Encoding
   ])

(def xapi-alternate-request-interceptor
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
                                   (try (java.lang.Double/parseDouble (second (cstr/split ?q #"=")))
                                         (catch java.lang.NumberFormatException _
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

;; Etag
(defn- sha-1 ^String [^String s]
  (apply str
         (map
          #(.substring
            (Integer/toString
             (+ (bit-and % 0xff) 0x100) 16) 1)
          (.digest (MessageDigest/getInstance "SHA-1")
                   (.getBytes s)))))

(defmulti calculate-etag class)

(defmethod calculate-etag String [s]
  (sha-1 s))

(defmethod calculate-etag (Class/forName "[B") [^bytes bs]
  (apply str
         (map
          #(.substring
            (Integer/toString
             (+ (bit-and % 0xff) 0x100) 16) 1)
          (.digest (MessageDigest/getInstance "SHA-1")
                   bs))))

(defmethod calculate-etag File [^File f]
  (sha-1 (str (.lastModified f) "-" (.length f))))

(defmethod calculate-etag ByteBuffer [^ByteBuffer bb]
  (calculate-etag (.array bb)))

(defmethod calculate-etag :default [x]
  (sha-1 (str (hash x))))

;; TODO: handle weak etags
(def etag-string-pattern
  #"\w+")

(defn etag-header->etag-set
  [etag-header]
  (into #{} (re-seq etag-string-pattern etag-header)))

(defn tap [x] (clojure.pprint/pprint x) x)

(defn execute-sync
  "Force pre-routing execution to handle the last enter interceptor
   Synchronously."
  [ctx]
  (let [{{route-interceptors :interceptors
          :as route} :route
         :as routed-ctx}
        (chain/execute-only (chain/terminate-when ctx :route) :enter)
        last-interceptor (last route-interceptors)
        butlast-ctx (chain/execute-only
                     (chain/terminate-when
                      ctx
                      (fn [ctx]
                        ;; Post routing, when there is a terminal interceptor
                        (when (:route ctx)
                          ;; When we're down to the last one
                          (some-> ctx
                                  ::chain/queue
                                  peek
                                  (= last-interceptor))))) :enter)]
    (if (get-in butlast-ctx [:response :status])
      butlast-ctx
      (let [last-i-enter-fn (:enter last-interceptor)
            response-ctx (last-i-enter-fn butlast-ctx)]
        (cond-> response-ctx
          (cs/read-port? response-ctx)
          a/<!!)))))

(declare etag-leave)

(defn etag-enter [{:keys [request] :as ctx}]
  (let [{{:strs [if-match if-none-match]} :headers
         method :request-method} request]
    (if (and (#{:put :post :delete} method)
             (or if-match if-none-match))
      (let [get-ctx (delay
                     (etag-leave
                      (try
                        (execute-sync
                         (-> ctx
                             (update :request dissoc :body)
                             (assoc-in [:request
                                        :request-method]
                                       :get)))
                        (catch Exception _
                          (merge ctx
                                 {:request (assoc request :request-method :get)
                                  :response {:status 400
                                             :body ""}})))))
            ;; _ (clojure.pprint/pprint @get-ctx)
            if-match-ok? (case if-match
                           nil true
                           "*" (= 200
                                  (get-in @get-ctx
                                          [:response
                                           :status]))
                           (contains? (etag-header->etag-set if-match)
                                      (::etag @get-ctx)))

            if-none-match-ok? (case if-none-match
                                nil true
                                "*" (= 404
                                       (get-in @get-ctx
                                               [:response
                                                :status]))
                                (not (contains? (etag-header->etag-set if-none-match)
                                                (::etag @get-ctx))))]
        (if (and if-match-ok? if-none-match-ok?)
          ctx
          (let [{{route-interceptors :interceptors
                  :as route} :route
                 :as after-route-ctx} (chain/execute-only (chain/terminate-when ctx :route) :enter)]
            (-> ctx
                ;; Run the leave stuff
                (dissoc ::chain/queue)
                (chain/enqueue (mapv #(assoc % :enter nil)
                                     route-interceptors))
                ;; Set the error response
                (assoc :response
                       (or
                        ;; If the params are not valid for the GET
                        ;; then the status is contagious
                        (let [get-response (:response @get-ctx)]
                          (when (= 400 (:status get-response))
                            {:status 400}))
                        ;; If the enter interceptors (except the handler)
                        ;; for this request would return a 400
                        (when-let [error-response
                                   (some-> ctx
                                           (dissoc ::chain/queue)
                                           (chain/enqueue (into []
                                                                (butlast route-interceptors)))
                                           (chain/execute-only :enter)
                                           :response
                                           )]
                          (when (= 400 (:status error-response))
                            error-response))
                        ;; Otherwise, it's a precon fail
                        {:status 412}))))))
      ctx)))

(defn- quote-etag [etag]
  (str "\"" etag "\""))

(defn etag-leave
  [{:keys [request response] :as ctx}]
  (if (#{:get :head} (:request-method request))
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
          (update-in [:response :headers] merge {"ETag" (quote-etag etag)})))
    ctx))

(def etag-interceptor
  (i/interceptor
   {:name ::etag
    :enter etag-enter
    :leave etag-leave}))

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

;; Time Requests

(def request-timer
  (i/interceptor
   {:name ::request-timer
    :enter (fn [ctx]
             (assoc ctx ::request-enter-ms (System/currentTimeMillis)))
    :leave (fn [ctx]
             (let [request-ms (- (System/currentTimeMillis)
                                 (::request-enter-ms ctx))
                   {:keys [path-info request-method]} (:request ctx)]
               (log/histogram "lrs request-ms all"
                              request-ms)
               (log/histogram (format "lrs request-ms %s %s"
                                      path-info
                                      request-method)
                              request-ms)
               ctx))}))

;; Combined interceptors

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
              request-logger http/log-request
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
             (cond-> []
               ;; For Jetty, ensure that request bodies are drained.
               ;; (= server-type :jetty) (conj util/ensure-body-drained)
               (some? request-logger) (conj (io.pedestal.interceptor/interceptor request-logger))
               (some? allowed-origins) (conj (cors/allow-origin allowed-origins))
               (some? not-found-interceptor) (conj (io.pedestal.interceptor/interceptor not-found-interceptor))
               (or enable-session enable-csrf) (conj (middlewares/session (or enable-session {})))
               (some? enable-csrf) (into [(body-params/body-params (:body-params enable-csrf (body-params/default-parser-map)))
                                          (csrf/anti-forgery enable-csrf)])
               true (conj (middlewares/content-type {:mime-types ext-mime-types}))
               true (conj route/query-params)
               true (conj xapi-method-param)
               true (conj (route/method-param :method))
               (some? secure-headers) (conj (sec-headers/secure-headers secure-headers))
               ;; The etag interceptor may mess with routes, so it's important not to have any
               ;; important leave stuff after it in the defaults
               true (conj etag-interceptor)
               ;; TODO: If all platforms support async/NIO responses, we can bring this back
               ;(not (nil? resource-path)) (conj (middlewares/fast-resource resource-path))
               (some? resource-path) (conj (middlewares/resource resource-path))
               (some? file-path) (conj (middlewares/file file-path))
               true (conj (route/router processed-routes router))))
      service-map)))

(def common-interceptors [x-forwarded-for-interceptor
                          http/json-body
                          ;; etag-interceptor

                          ;; TODO: multiparts, etc
                          ;; i/multipart-mixed


                          (body-params/body-params
                           (body-params/default-parser-map
                            :json-options {:key-fn str}))

                          xapi/alternate-request-syntax-interceptor
                          #_{:name ::foo
                           :enter (fn [ctx] (clojure.pprint/pprint (:request ctx))
                                    ctx)}
                          ;; etag-interceptor
                          ;; route/query-params
                          ;; xapi-alternate-request-headers-interceptor
                          ;; keyword-params
                          ;; keyword-body-params

                          #_(i/authentication auth/backend auth/cantilever-backend)
                          #_(i/access-rules {:rules auth/rules
                                             :on-error auth/error-response})

                          set-xapi-version-interceptor
                          xapi-ltags-interceptor
                          ])

(def doc-interceptors-base
  [x-forwarded-for-interceptor
   xapi/alternate-request-syntax-interceptor
   ;; etag-interceptor
   ;; route/query-params
   ;; xapi-alternate-request-headers-interceptor
   ;; keyword-params
   ;; keyword-body-params

   #_(i/authentication auth/backend auth/cantilever-backend)
   #_(i/access-rules {:rules auth/rules
                      :on-error auth/error-response})

   set-xapi-version-interceptor
   xapi-ltags-interceptor
   ])

(def xapi-protected-interceptors
  [require-xapi-version-interceptor])
