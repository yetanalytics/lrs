(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs.protocol.xapi.statements :as statements-proto]))

(defn error-response
  "Define error responses for statement resource errors"
  [exi]
  (let [exd (ex-data exi)]
    (case (:type exd)
      ::statements-proto/statement-conflict
      {:status 409
       :body
       {:error
        (merge {:message (.getMessage exi)}
               (select-keys exd [:statement
                                 :extant-statement]))}}
      ::statements-proto/invalid-voiding-statement
      {:status 400
       :body
       {:error
        (merge {:message (.getMessage exi)}
               (select-keys exd [:statement]))}}
      (throw exi))))

(def handle-put
  {:name ::handle-put
   :enter
   (fn [ctx]
     (assoc ctx :response
            (let [{params :xapi.statements.PUT.request/params
                   statement :xapi-schema.spec/statement
                   attachments :xapi.statements/attachments} (:xapi ctx)
                  s-id (:statementId params)
                  lrs (get ctx :com.yetanalytics/lrs)]
              (if (or (nil? (get statement "id"))
                      (= s-id (get statement "id")))
                (try (statements-proto/store-statements
                      lrs [(assoc statement "id" s-id)]
                      attachments)
                     {:status 204}
                     (catch clojure.lang.ExceptionInfo exi
                       (error-response exi)))
                {:status 400
                 :body
                 {:error
                  {:message "statementId param does not match Statement ID"
                   :statement-id-param s-id
                   :statement-id (get statement "id")}}}))))})

(def handle-post
  {:name ::handle-post
   :enter
   (fn [ctx]
     (assoc ctx :response
            (let [{?statements :xapi-schema.spec/statements
                   ?statement :xapi-schema.spec/statement
                   attachments :xapi.statements/attachments} (:xapi ctx)
                  lrs (get ctx :com.yetanalytics/lrs)
                  statements (or ?statements [?statement])]
              (try
                {:status 200
                 :body (statements-proto/store-statements
                        lrs
                        statements
                        attachments)}
                (catch clojure.lang.ExceptionInfo exi
                  (error-response exi))))))})

;; TODO: wrap attachment response
;; TODO: Last modfified https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#requirements-4
;; TODO: wrap ltags
;; TODO: wrap alt req. check
(def handle-get
  {:name ::handle-get
   :enter
   (fn [ctx]
     (assoc ctx :response
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  params (get-in ctx [:xapi :xapi.statements.GET.request/params] {})
                  ltags (get ctx :xapi/ltags [])]
              (try
                (if-let [result (statements-proto/get-statements
                                 lrs
                                 params
                                 ltags)]
                  {:status 200
                   :body result}
                  {:status 404})
                (catch clojure.lang.ExceptionInfo exi
                  (error-response exi))))))})
