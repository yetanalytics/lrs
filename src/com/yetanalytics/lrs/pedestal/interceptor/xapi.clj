(ns com.yetanalytics.lrs.pedestal.interceptor.xapi
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec.resources :as xsr]
            [io.pedestal.interceptor.chain :as chain]))

(defn coerce-params [params param-coercers]
  (if param-coercers
    (reduce-kv
     (fn [m pk pv]
       (assoc m
              pk
              (if-let [coercer-fn (get param-coercers pk)]
                (try (coercer-fn pv)
                     (catch java.lang.Exception e
                       (throw (ex-info "Param Coercion Error"
                                       {:type ::param-coercion-error
                                        :param-key pk
                                        :param-val pv}
                                       e))))
                pv)))
     {}
     params)
    params))

(defn params-interceptor
  "Interceptor factory, given a spec keyword, it validates params against it.
   coerce-params is a map of param to coercion function."
  [spec-kw & [param-coercers]]
  {:name (let [[k-ns k-name] ((juxt namespace name) spec-kw)]
           (keyword k-ns (str k-name "-interceptor")))
   :enter (fn [ctx]
            (try (let [params (-> ctx
                                  :request
                                  :params
                                  (coerce-params param-coercers))]
                   (if (s/valid? spec-kw params)
                     (assoc-in ctx [:xapi spec-kw] params)
                     (assoc (chain/terminate ctx)
                            :response
                            {:status 400
                             :body {:error
                                    {:message "Invalid Params"
                                     :spec-error (s/explain-str spec-kw params)}}})))
                 (catch clojure.lang.ExceptionInfo exi
                   (let [exd (ex-data exi)]
                     (if (= ::param-coercion-error (:type exd))
                       (assoc (chain/terminate
                               ctx)
                              :response
                              {:status 400
                               :body {:error
                                      {:message "Invalid Params, could not coerce"
                                       :param (name (:param-key exd))
                                       :value (name (:param-val exd))}}})
                       (throw exi))))))})
