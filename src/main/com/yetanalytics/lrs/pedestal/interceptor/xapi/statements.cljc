(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements
  (:require
   #?@(:clj [[cheshire.core :as json]
             [clojure.java.io :as io]
             [io.pedestal.log :as log]]
       :cljs [cljs.nodejs
              [goog.string :as gstring]
              goog.string.format
              [com.yetanalytics.lrs.util.log :as log]])
   [io.pedestal.interceptor.chain :as chain]
   [com.yetanalytics.lrs.pedestal.http.multipart-mixed :as multipart]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment :as attachment]
   [com.yetanalytics.lrs.xapi.statements :as ss]
   [com.yetanalytics.lrs :as lrs]
   [com.yetanalytics.lrs.protocol :as lrsp]

   [clojure.spec.alpha :as s :include-macros true]
   [xapi-schema.spec :as xs]
   [clojure.walk :as w]
   [clojure.core.async :as a :include-macros true]
   [clojure.string :as cs]
   [com.yetanalytics.lrs.auth :as auth])
  #?(:clj (:import [java.io InputStream OutputStream])))

(def fmt #?(:clj format :cljs gstring/format))

;; The general flow for these is 1. parse 2. validate 3. place in context

;; Multiparts
(s/def :multipart/content-type
  string?)
(s/def :multipart/content-length
  integer?)
(s/def :multipart/input-stream
  #?(:clj #(instance? InputStream %)
     :cljs string?))

(s/def ::X-Experience-API-Hash string?)
(s/def ::Content-Transfer-Encoding #{"binary"})

(s/def :multipart/headers
  (s/and (s/conformer w/keywordize-keys
                      w/stringify-keys)
         (s/keys :req-un [::X-Experience-API-Hash
                          ::Content-Transfer-Encoding])))

(s/def ::multipart
  (s/keys :req-un [:multipart/content-type
                   :multipart/content-length
                   :multipart/input-stream
                   :multipart/headers]))

(s/def :statement-part/content-type
  #{"application/json"})

(s/def :statement-part/headers
  (s/map-of string? string?))

(s/def ::statement-part
  (s/keys :req-un [:statement-part/content-type
                   :multipart/content-length
                   :multipart/input-stream
                   :statement-part/headers]))

(s/def ::xapi-multiparts
  (s/and
   (s/cat
    :statement-part ::statement-part
    :multiparts (s/* ::multipart))
   (fn [[_ & rest-mps]]
     (if (seq rest-mps)
       (apply distinct?
              (map #(get-in % [:headers "X-Experience-API-Hash"])
                   rest-mps))
       true))))

(s/def ::ctype-boundary
  #(re-matches
    #?(:clj #".*boundary\s*=\s*([a-zA-Z0-9\'\+\-\_]+|\"[a-zA-Z0-9'\(\)\+\_\,\-\.\/\:\=\?\s]+(?<!\s)\")"
       :cljs #".*") ;; TODO: fix this regex in cljs
    %))

;; TODO: figure out how to give back good errors to user here
;; TODO: figure out if we can short-circuit spec stuff
(def parse-multiparts
  "Parses and validates xapi statement multiparts.
  Puts statements in :json-params and multiparts (if any) in :multiparts."
  {:name ::parse-multiparts
   :enter (fn [{:keys [request] :as ctx}]
            (let [content-type (or
                                (:content-type request)
                                (get-in request [:headers "content-type"])
                                (get-in request [:headers "Content-Type"]))]
              (if (.startsWith ^String (cs/trim content-type) "multipart")
                (if-let [boundary (multipart/find-boundary content-type)]
                  (try (let [parts-seq (multipart/parse-parts (:body request)
                                                              boundary)]
                         (if-let [error (s/explain-data ::xapi-multiparts parts-seq)]
                           (assoc (chain/terminate ctx)
                                  :response
                                  {:status 400
                                   :body
                                   {:error
                                    {:message "Invalid Multipart Request"

                                     }}})
                           (-> ctx
                               (assoc-in [:request :json-params]
                                         #?(:clj (with-open [rdr (io/reader (-> parts-seq
                                                                                first
                                                                                :input-stream))]
                                                   (json/parse-stream rdr))
                                            :cljs (-> (.parse js/JSON (-> parts-seq
                                                                          first
                                                                          :input-stream))
                                                      js->clj)))
                               (assoc-in [:request :multiparts]
                                         (into [] (rest parts-seq))))))
                       (catch #?(:clj clojure.lang.ExceptionInfo
                                 :cljs ExceptionInfo) exi
                         (let [exd (ex-data exi)]
                           (case (:type exd)
                             ::multipart/invalid-multipart-body
                             (assoc (chain/terminate ctx)
                                    :response
                                    {:status 400
                                     :body
                                     {:error
                                      {:message "Invalid Multipart Body"}}})
                             ::multipart/incomplete-multipart
                             (assoc (chain/terminate ctx)
                                    :response
                                    {:status 400
                                     :body
                                     {:error
                                      {:message "Incomplete Multipart Request"}}})
                             ::multipart/too-much-content
                             (assoc (chain/terminate ctx)
                                    :response
                                    {:status 413
                                     :body
                                     {:error
                                      {:message "Too much content"}}})
                             (throw exi))))
                       (catch #?(:clj com.fasterxml.jackson.core.JsonParseException
                                 :cljs js/Error) _ ;; TODO: detect json parse errs in cljs
                         (assoc (chain/terminate ctx)
                                :response
                                {:status 400
                                 :body {:error {:message "Invalid Statement JSON in multipart"}}})))
                  (assoc (chain/terminate ctx)
                         :response
                         {:status 400
                          :body {:error {:message "Invalid Multipart Boundary"
                                         :content-type content-type}}}))
                ctx)))})


(def single-or-multiple-statement-spec
  (s/or :single ::xs/statement
        :multiple ::xs/statements))

(def validate-request-statements
          "Validate statement JSON structure and return a 400 if it is missing or
  not valid. Puts statement data under the spec it satisfies in context :xapi"
          {:name ::validate-request-statements
           :enter (fn [ctx]
                    (let [multiparts (get-in ctx [:request :multiparts] [])]
                      (if-let [statement-data (get-in ctx [:request :json-params])]
                        (try (condp s/valid? statement-data
                               ::xs/statement
                               (let [[_ valid-multiparts] (attachment/validate-statements-multiparts
                                                           [statement-data] multiparts)]
                                 (update ctx
                                         :xapi
                                         assoc
                                         ::xs/statement statement-data
                                         :xapi.statements/attachments
                                         (attachment/save-attachments
                                          valid-multiparts)))
                               ::xs/statements
                               (let [[_ valid-multiparts] (attachment/validate-statements-multiparts
                                                           statement-data multiparts)]
                                 (update ctx
                                         :xapi
                                         assoc
                                         ::xs/statements statement-data
                                         :xapi.statements/attachments
                                         (attachment/save-attachments
                                          valid-multiparts)))
                               (assoc (chain/terminate ctx)
                                      :response
                                      {:status 400
                                       :body {:error {:message "Invalid Statement Data"
                                                      :statement-data statement-data
                                                      :spec-error (s/explain-str single-or-multiple-statement-spec
                                                                                 statement-data)}}}))
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs ExceptionInfo) exi
                               (assoc (chain/terminate ctx)
                                      :response
                                      (let [exd (ex-data exi)]
                                        (log/debug :msg "Statement Validation Exception" :exd exd)
                                        {:status (if (= ::attachment/attachment-save-failure (:type exd))
                                                   500
                                                   400)
                                         :body
                                         {:error {:message #?(:clj (.getMessage exi)
                                                              :cljs (.-message exi))}}})))
                             (finally (attachment/close-multiparts! multiparts)))
                        (assoc (chain/terminate ctx)
                               :response
                               {:status 400
                                :body {:error {:message "No Statement Data Provided"}}}))))})

(def set-consistent-through
  {:name ::set-consistent-through
   :leave (fn [{auth-identity ::auth/identity
                :keys [com.yetanalytics/lrs] :as ctx}]
            (cond
              (lrsp/statements-resource-async? lrs)
              (a/go
                (assoc-in ctx [:response :headers "X-Experience-API-Consistent-Through"]
                          (a/<! (lrs/consistent-through-async lrs ctx auth-identity))))
              (lrsp/statements-resource? lrs)
              (assoc-in ctx [:response :headers "X-Experience-API-Consistent-Through"]
                        (lrs/consistent-through lrs ctx auth-identity))
              :else
              (assoc-in ctx [:response :headers "X-Experience-API-Consistent-Through"]
                        (ss/now-stamp))))})

#?(:clj (defn lazy-statement-result [{:keys [statements
                                             more]}
                                     ^OutputStream os]
          (with-open [w (io/writer os)]
            ;; Write everything up to the beginning of the statements
            (.write w "{\"statements\": [")
            (doseq [x (interpose :comma statements)]
              (if (= :comma x)
                (.write w ",")
                (json/with-writer [w {}]
                  (json/write x))))
            (let [^String terminal (if more
                                     (fmt "], \"more\": \"%s\"}" more)
                                     "]}")]
              (.write w
                      terminal)))))

(defn json-string [x]
  #?(:clj (json/generate-string x)
     :cljs (.stringify js/JSON (clj->js x))))

(defn lazy-statement-result-async
  [statement-result-chan]
  (let [body-chan (a/chan)]
    (a/go
      (loop [stage :init
             s-count 0]
        (if-let [x (a/<! statement-result-chan)]
          (case x
            :statements
            (do (a/>! body-chan "{\"statements\":[")
                (recur :statements s-count))
            :more
            (do (a/>! body-chan
                      (fmt "],\"more\":\"%s\"}"
                           (a/<! statement-result-chan)))
                (recur :more s-count))

            (do
              ;; maybe Comma
              (when (< 0 s-count)
                (a/>! body-chan ","))
              ;; Write statement
              (a/>! body-chan (json-string x))
              (recur :statements (inc s-count))))
          ;; if no more link, close
          (when (= stage :statements)
            (a/>! body-chan "]}"))))
      (a/close! body-chan))
    body-chan))

(defn accept-html?
  "Did the user request html?"
  [ctx]
  (and
   (:com.yetanalytics.lrs.pedestal.interceptor/statement-html? ctx)
   (some-> ctx
           ^String (get-in [:request
                            :headers
                            "accept"])
           (.startsWith "text/html"))))

(defn collect-result
  "Collect an async statement result as clojure data"
  [statement-result-chan]
  (a/go
    (let [[_
           statements
           _
           [?more]] (partition-by
                     keyword?
                     (a/<!
                      (a/into []
                              statement-result-chan)))]
      (cond-> {:statements statements}
        ?more (assoc :more ?more)))))
