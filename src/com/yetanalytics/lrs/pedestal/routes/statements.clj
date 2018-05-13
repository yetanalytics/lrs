(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :as att-resp]))

(defn error-response
  "Define error responses for statement resource errors"
  [exi]
  (let [exd (ex-data exi)]
    (case (:type exd)
      ::p/statement-conflict
      {:status 409
       :body
       {:error
        (merge {:message (.getMessage exi)}
               (select-keys exd [:statement
                                 :extant-statement]))}}
      ::p/invalid-voiding-statement
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
                (try (lrs/store-statements
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
            (try
              (let [{?statements :xapi-schema.spec/statements
                     ?statement :xapi-schema.spec/statement
                     attachments :xapi.statements/attachments} (:xapi ctx)
                    lrs (get ctx :com.yetanalytics/lrs)
                    statements (or ?statements [?statement])
                    {:keys [statement-ids]} (lrs/store-statements
                                             lrs
                                             statements
                                             attachments)]
                {:status 200
                 :body statement-ids})
              (catch clojure.lang.ExceptionInfo exi
                (error-response exi)))))})

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
                  ltags (get ctx :xapi/ltags [])
                  attachments-query? (:attachments params)
                  {:keys [statement-result
                          statement
                          attachments
                          etag]}
                  (lrs/get-statements
                   lrs
                   params
                   ltags)]
              (try
                (if-let [s-data (or statement statement-result)]
                  (if (and attachments-query?
                           (seq attachments))
                    {:status 200
                     :headers (cond-> {"Content-Type" att-resp/content-type}
                                etag (assoc "etag" etag))
                     :body (att-resp/build-multipart
                            s-data
                            attachments)}
                    {:status 200
                     :body s-data})
                  {:status 404})
                (catch clojure.lang.ExceptionInfo exi
                  (error-response exi))))))})
