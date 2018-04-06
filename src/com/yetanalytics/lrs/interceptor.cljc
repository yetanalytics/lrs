(ns com.yetanalytics.lrs.interceptor.cljc
  "xAPI Route Interceptors"
  (:require [clojure.string :as cstr])
  (:import [java.security MessageDigest]
           [java.io File]))

;; Enter
(defn lrs-interceptor
  "An interceptor that takes an lrs implementation and puts it in the context"
  [lrs]
  {:name ::lrs
   :enter (fn [ctx]
            (assoc ctx :com.yetanalytics/lrs lrs))})

(def require-xapi-version-interceptor
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
                (assoc ctx :response
                       {:status 400
                        :body
                        {:error {:message "X-Experience-API-Version header invalid!"}}}))
              (assoc ctx :response
                     {:status 400
                      :body
                      {:error {:message "X-Experience-API-Version header required!"}}})))})

(def x-forwarded-for-interceptor
  {:name ::x-forwarded-for
   :enter (fn [{:keys [request] :as ctx}]
            (if-let [xff (get-in request [:headers "x-forwarded-for"])]
              (update ctx :request assoc :remote-addr (last (cstr/split xff #"\s*,\s*")))
              ctx))})

(defn xapi-attachments-interceptor
  "Interceptor factory that takes a multipart parsing function, and adds xapi
   attachments to the request, if it is multipart/mixed;"
  [parse-request]
  {:name ::xapi-attachments
   :enter (fn [{:keys [request] :as ctx}]
            (if-let [attachments (parse-request request)]
              (assoc ctx :xapi/attachments attachments)
              ctx))})

(def valid-alt-request-headers
  ["Authorization" "X-Experience-API-Version" "Content-Type"
   "Content-Length" "If-Match" "If-None-Match" "Accept-Language"
   "Accept" "Accept-Encoding"])

(defn xapi-alternate-request-headers-interceptor
  "Interceptor factory that takes a form-parser fn and handles xapi alternate
   request syntax"
  [form-parser]
  {:name ::xapi-alternate-request-headers
   :enter (fn [{:keys [request] :as ctx}]
            (if (some-> request :params :method)
              (let [request (form-parser request)
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
              ctx))})

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
  {:name ::xapi-ltags
   :enter (fn [ctx]
            (if-let [accept-language (get-in ctx [:request :headers "accept-language"])]
              (try
                (assoc ctx :xapi/ltags (parse-accept-language accept-language))
                (catch clojure.lang.ExceptionInfo exi
                  (let [exd (ex-data exi)]
                    (if (= (:type exd) ::invalid-accept-language-header)
                      (assoc ctx :response {:status 400
                                            :body
                                            {:message (.getMessage exi)
                                             :header accept-language}})
                      (throw exi)))))
              ctx))})

;; Leave
(def set-xapi-version-interceptor
  {:name ::set-xapi-version
   :leave (fn [ctx]
            (assoc-in ctx [:response :headers "X-Experience-API-Version"]
                      "1.0.3"))})

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

(defmethod calculate-etag File [^File f]
  (sha-1 (str (.lastModified f) "-" (.length f))))

(defmethod calculate-etag :default [x]
  (sha-1 (str (hash x))))


(defn- quote-etag [etag]
  (str "\"" etag "\""))

(def etag-interceptor
  {:name ::etag
   :leave (fn [{:keys [request response] :as ctx}]
            (let [if-none-match (get-in request [:headers "if-none-match"])
                  etag (get-in response [:headers "etag"]
                               (calculate-etag (:body response)))]
              (if (= etag if-none-match)
                (assoc ctx :response
                       {:status 304 :body "" :headers {"etag" (quote-etag etag)}})
                (assoc-in ctx [:response :headers] {"etag" (quote-etag etag)}))))})

;; Combo
(def require-and-set-xapi-version-interceptor
  (merge
   require-xapi-version-interceptor
   set-xapi-version-interceptor
   {:name ::require-and-set-xapi-version}))

;; TODO: Port the rest of the interceptors
