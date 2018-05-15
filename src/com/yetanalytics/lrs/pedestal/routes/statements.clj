(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :as att-resp]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            [clojure.core.async :as a]))

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
   (fn [{:keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (if (p/statements-resource-async? lrs)
       (a/go (assoc ctx :response
                    (let [{params :xapi.statements.PUT.request/params
                           statement :xapi-schema.spec/statement
                           attachments :xapi.statements/attachments} xapi
                          s-id (:statementId params)
                          lrs (get ctx :com.yetanalytics/lrs)]
                      (if (or (nil? (get statement "id"))
                              (= s-id (get statement "id")))
                        (let [{:keys [error]}
                              (a/<! (lrs/store-statements-async
                                     lrs [(assoc statement "id" s-id)]
                                     attachments))]
                          (if error
                            (error-response error)
                            {:status 204}))
                        {:status 400
                         :body
                         {:error
                          {:message "statementId param does not match Statement ID"
                           :statement-id-param s-id
                           :statement-id (get statement "id")}}}))))
       (assoc ctx :response
              (let [{params :xapi.statements.PUT.request/params
                     statement :xapi-schema.spec/statement
                     attachments :xapi.statements/attachments} xapi
                    s-id (:statementId params)
                    lrs (get ctx :com.yetanalytics/lrs)]
                (if (or (nil? (get statement "id"))
                        (= s-id (get statement "id")))
                  (let [{:keys [error]}
                        (lrs/store-statements
                         lrs [(assoc statement "id" s-id)]
                         attachments)]
                    (if error
                      (error-response error)
                      {:status 204}))
                  {:status 400
                   :body
                   {:error
                    {:message "statementId param does not match Statement ID"
                     :statement-id-param s-id
                     :statement-id (get statement "id")}}})))))})

(def handle-post
  {:name ::handle-post
   :enter
   (fn [{:keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (if (p/statements-resource-async? lrs)
       (a/go
         (assoc ctx :response
                (let [{?statements :xapi-schema.spec/statements
                       ?statement :xapi-schema.spec/statement
                       attachments :xapi.statements/attachments} (:xapi ctx)
                      lrs (get ctx :com.yetanalytics/lrs)
                      statements (or ?statements [?statement])
                      {:keys [statement-ids
                              error]} (a/<! (lrs/store-statements-async
                                             lrs
                                             statements
                                             attachments))]
                  (if error
                    (error-response error)
                    {:status 200
                     :body statement-ids}))))
       (assoc ctx :response
              (let [{?statements :xapi-schema.spec/statements
                     ?statement :xapi-schema.spec/statement
                     attachments :xapi.statements/attachments} (:xapi ctx)
                    lrs (get ctx :com.yetanalytics/lrs)
                    statements (or ?statements [?statement])
                    {:keys [statement-ids
                            error]} (lrs/store-statements
                                     lrs
                                     statements
                                     attachments)]
                (if error
                  (error-response error)
                  {:status 200
                   :body statement-ids})))))})

;; TODO: wrap attachment response
;; TODO: Last modfified https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#requirements-4
;; TODO: wrap ltags
;; TODO: wrap alt req. check
(def handle-get
  {:name ::handle-get
   :enter
   (fn [{:keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (if (p/statements-resource-async? lrs)
       (a/go
         (assoc ctx :response
                (let [lrs (get ctx :com.yetanalytics/lrs)
                      params (get-in ctx [:xapi :xapi.statements.GET.request/params] {})
                      ltags (get ctx :xapi/ltags [])
                      attachments-query? (:attachments params)
                      {:keys [statement-result
                              statement
                              attachments
                              etag]}
                      (a/<! (lrs/get-statements-async
                             lrs
                             params
                             ltags))]
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
                        (if statement-result
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (partial si/lazy-statement-result
                                          s-data)}
                          {:status 200
                           :body s-data}))
                      {:status 404})
                    (catch clojure.lang.ExceptionInfo exi
                      (error-response exi))))))
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
                      (if statement-result
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (partial si/lazy-statement-result
                                        s-data)}
                        {:status 200
                         :body s-data}))
                    {:status 404})
                  (catch clojure.lang.ExceptionInfo exi
                    (error-response exi)))))))})
