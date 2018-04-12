(ns com.yetanalytics.lrs.pedestal.interceptor.xapi
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [io.pedestal.interceptor.chain :as chain]
            [cheshire.core :as json]))

(defn conform-cheshire [spec-kw x]
  (binding [xsr/*read-json-fn* json/parse-string-strict
            xsr/*write-json-fn* json/generate-string]
    (s/conform spec-kw x)))

(defn params-interceptor
  "Interceptor factory, given a spec keyword, it validates params against it.
   coerce-params is a map of param to coercion function."
  [spec-kw]
  {:name (let [[k-ns k-name] ((juxt namespace name) spec-kw)]
           (keyword k-ns (str k-name "-interceptor")))
   :enter (fn [ctx]
            (let [raw-params (get-in ctx [:request :params] {})
                  params (conform-cheshire spec-kw raw-params)]
              (if-not (= ::s/invalid params)
                (assoc-in ctx [:xapi spec-kw] params)
                (assoc (chain/terminate ctx)
                       :response
                       {:status 400
                        :body {:error
                               {:message "Invalid Params"
                                :spec-error (s/explain-str spec-kw raw-params)}}}))))})
