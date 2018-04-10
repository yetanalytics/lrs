(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements
  (:require
   [com.yetanalytics.lrs.pedestal.http.multipart-mixed :as multipart]
   [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment :as attachment]
   [io.pedestal.interceptor.chain :as chain]
   [clojure.spec.alpha :as s]
   [xapi-schema.spec :as xs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.walk :as w]
   [io.pedestal.log :as log])
  (:import [java.time Instant]))

;; The general flow for these is 1. parse 2. validate 3. place in context

;; Multiparts
(s/def :multipart/content-type
  string?)
(s/def :multipart/content-length
  integer?)
(s/def :multipart/input-stream
  #(instance? java.io.ByteArrayInputStream %))

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
    #".*boundary\s*=\s*([a-zA-Z0-9\'\+\-\_]+|\"[a-zA-Z0-9'\(\)\+\_\,\-\.\/\:\=\?\s]+(?<!\s)\")"
    %))

;; TODO: figure out how to give back good errors to user here
;; TODO: figure out if we can short-circuit spec stuff
(def parse-multiparts
  "Parses and validates xapi statement multiparts.
  Puts statements in :json-params and multiparts (if any) in :multiparts."
  {:name ::parse-multiparts
   :enter (fn [{:keys [request] :as ctx}]
            (let [content-type (:content-type request)]
              (if (.startsWith ^String content-type "multipart/mixed")
                (if (s/valid? ::ctype-boundary content-type)
                  (try (let [parts-seq (multipart/parse-request request)]
                         (if-let [error (s/explain-data ::xapi-multiparts parts-seq)]
                           (assoc (chain/terminate ctx)
                                  :response
                                  {:status 400
                                   :body
                                   {:error
                                    {:message "Invalid Multipart Request"}}})
                           (-> ctx
                               (assoc-in [:request :json-params]
                                         (with-open [rdr (io/reader (-> parts-seq
                                                                        first
                                                                        :input-stream))]
                                           (json/parse-stream rdr)))
                               (assoc-in [:request :multiparts]
                                         (into [] (rest parts-seq))))))
                       (catch clojure.lang.ExceptionInfo exi
                         (let [exd (ex-data exi)]
                           (case (:type exd)
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
                       (catch com.fasterxml.jackson.core.JsonParseException _
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

;; TODO: Wire up attachment + sig validation here
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
                     (catch clojure.lang.ExceptionInfo exi
                       (assoc (chain/terminate ctx)
                              :response
                              (let [exd (ex-data exi)]
                                (log/debug :msg "Statement Validation Exception" :exd exd)
                                {:status (if (= ::attachment/attachment-save-failure (:type exd))
                                           500
                                           400)
                                 :body
                                 {:error {:message (.getMessage exi)}}})))
                     (finally (attachment/close-multiparts! multiparts)))
                (assoc (chain/terminate ctx)
                       :response
                       {:status 400
                        :body {:error {:message "No Statement Data Provided"}}}))))})

;; TODO: wire this up to something?!?
(def set-consistent-through
  {:name ::set-consistent-through
   :leave (fn [ctx]
            (assoc-in ctx [:response :headers "X-Experience-Api-Consistent-Through"]
                      (str (Instant/now))))})
