(ns com.yetanalytics.lrs.pedestal.interceptor.xapi.statements
  (:require [io.pedestal.interceptor.chain :as chain]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.time Instant]))

(def single-or-multiple-statement-spec
  (s/or :single ::xs/statement
        :multiple ::xs/statements))

(def validate-request-statements
  "Validate statement JSON and return a 400 if it is not valid"
  {:name ::validate-request-statements
   :enter (fn [ctx]
            ;; (println "multis" (-> ctx :request :multiparts))
            (let [content-type (get-in ctx [:request :content-type])]
              (if-let [statement-data (cond
                                        (.startsWith content-type "application/json")
                                        (get-in ctx [:request :json-params])
                                        ;; multipart/mixed
                                        #_(.startsWith content-type "multipart/mixed")
                                        #_(when-let [])
                                        :else nil)]
                (if (s/valid? single-or-multiple-statement-spec
                              statement-data)
                  ctx
                  (assoc (chain/terminate ctx)
                         :response
                         {:status 400
                          :body {:error {:message "Invalid Statement Data"
                                         :statement-data statement-data
                                         :spec-error (s/explain-str single-or-multiple-statement-spec
                                                                    statement-data)}}}))
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
