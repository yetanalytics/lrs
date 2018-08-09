(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :as att-resp]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            [clojure.core.async :as a :include-macros true]))

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

(defn put-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [error] :as lrs-response}]
  (assoc ctx
         :response
         (if error
           (error-response error)
           {:status 204})))

(def handle-put
  {:name ::handle-put
   :enter
   (fn [{:keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (let [{params :xapi.statements.PUT.request/params
            statement :xapi-schema.spec/statement
            attachments :xapi.statements/attachments} xapi
           s-id (:statementId params)
           bad-params-response (assoc ctx
                                      :response
                                      {:status 400
                                       :body
                                       {:error
                                        {:message "statementId param does not match Statement ID"
                                         :statement-id-param s-id
                                         :statement-id (get statement "id")}}})]
       (if (p/statements-resource-async? lrs)
         (a/go (if (or (nil? (get statement "id"))
                       (= s-id (get statement "id")))
                 (put-response ctx (a/<! (lrs/store-statements-async
                                          lrs [(assoc statement "id" s-id)]
                                          attachments)))
                 bad-params-response))

         (if (or (nil? (get statement "id"))
                 (= s-id (get statement "id")))
           (put-response ctx (lrs/store-statements
                              lrs [(assoc statement "id" s-id)]
                              attachments))
           bad-params-response))))})

(defn post-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [statement-ids
           error] :as lrs-response}]
  (assoc ctx :response
         (if error
           (error-response error)
           {:status 200
            :body statement-ids})))

(def handle-post
  {:name ::handle-post
   :enter
   (fn [{:keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (let [{?statements :xapi-schema.spec/statements
            ?statement :xapi-schema.spec/statement
            attachments :xapi.statements/attachments} (:xapi ctx)
           statements (or ?statements [?statement])]
       (if (p/statements-resource-async? lrs)
         (a/go
           (post-response ctx (a/<! (lrs/store-statements-async
                                     lrs
                                     statements
                                     attachments))))
         (post-response ctx (lrs/store-statements
                             lrs
                             statements
                             attachments)))))})

;; TODO: wrap attachment response
;; TODO: Last modfified https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#requirements-4
;; TODO: wrap ltags
;; TODO: wrap alt req. check

(defn get-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [statement-result
           statement
           attachments
           etag]}]
  (assoc ctx
         :response
         (try
           (if-let [s-data (or statement statement-result)]
             (if (and (get-in xapi [:xapi.statements.GET.request/params :attachments])
                      (seq attachments))
               {:status 200
                :headers (cond-> {"Content-Type" att-resp/content-type}
                           etag (assoc "etag" etag))
                :body
                ;; shim, the protocol will be expected to return this
                (att-resp/build-multipart-async
                 (let [c (a/chan)]
                   (a/onto-chan
                    c
                    (if (some? statement)
                      (concat (list :statement statement)
                              (cons :attachments attachments))
                      (concat (cons :statements
                                    (:statements statement-result))
                              (when-let [more (:more statement-result)]
                                (list :more more))
                              (cons :attachments attachments))))
                   c))}
               (if statement-result
                 {:status 200
                  :headers {"Content-Type" "application/json"}
                  :body (si/lazy-statement-result-async
                         (let [c (a/chan)]
                           (a/onto-chan
                            c
                            (concat (cons :statements
                                          (:statements statement-result))
                                    (when-let [more (:more statement-result)]
                                      (list :more more))))
                           c))}
                 {:status 200
                  :body s-data}))
             {:status 404})
           (catch clojure.lang.ExceptionInfo exi
             (error-response exi)))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn [{:keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (let [params (get-in ctx [:xapi :xapi.statements.GET.request/params] {})
           ltags (get ctx :xapi/ltags [])]
       (if (p/statements-resource-async? lrs)
         (let [r-chan (lrs/get-statements-async
                       lrs
                       params
                       ltags)]
           (a/go
             (assoc ctx :response
                    (cond ;; Special handling to see if it's a 404
                      ((some-fn :statementId :voidedStatementId)
                       params)
                      (let [_ (a/<! r-chan)
                            ?statement (a/<! r-chan)
                            ]
                        (if (map? ?statement)
                          (if (:attachments params)
                            {:status 200
                             :headers {"Content-Type" att-resp/content-type}
                             :body
                             (att-resp/build-multipart-async
                              (let [c (a/chan)]
                                (a/onto-chan c (a/<! (a/into [:statement
                                                              ?statement]
                                                             r-chan)))
                                c))}
                            {:status 200
                             :body ?statement})
                          {:status 404}))
                      (:attachments params)
                      {:status 200
                       :headers {"Content-Type" att-resp/content-type}
                       :body
                       (att-resp/build-multipart-async
                        r-chan)}
                      :else
                      {:status 200
                       :headers {"Content-Type" "application/json"}
                       :body
                       (si/lazy-statement-result-async
                        r-chan)}))))
         (get-response ctx (lrs/get-statements
                            lrs
                            params
                            ltags)))))})
