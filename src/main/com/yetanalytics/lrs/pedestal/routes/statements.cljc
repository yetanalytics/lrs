(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :as att-resp]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            [clojure.spec.alpha :as s :include-macros true]))

(defn error-response
  "Define error responses for statement resource errors. Stick unhandled errors
  on the context"
  [ctx exi]
  (let [exd (ex-data exi)]
    (case (:type exd)
      ::p/statement-conflict
      (assoc ctx
             :response
             {:status 409
              :body
              {:error
               ;; TODO: why are we returning the message here?
               (merge {:message (#?(:clj .getMessage
                                    :cljs .-message) exi)}
                      (select-keys exd [:statement
                                        :extant-statement]))}})
      ::p/invalid-voiding-statement
      (assoc ctx
             :response
             {:status 400
              :body
              {:error
               (merge {:message (#?(:clj .getMessage
                                    :cljs .-message) exi)}
                      (select-keys exd [:statement]))}})
      (assoc ctx
             :io.pedestal.interceptor.chain/error
             exi))))

(s/fdef put-response
  :args (s/cat :ctx map?
               :store-statements-ret ::p/store-statements-ret))

(defn put-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [error] :as lrs-response}]
  (if error
    (error-response ctx error)
    (assoc ctx :response {:status 204})))

(def handle-put
  {:name ::handle-put
   :enter
   (fn [{auth-identity ::auth/identity
         :keys [xapi
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
                                          lrs auth-identity
                                          [(assoc statement "id" s-id)]
                                          attachments)))
                 bad-params-response))

         (if (or (nil? (get statement "id"))
                 (= s-id (get statement "id")))
           (put-response ctx (lrs/store-statements
                              lrs auth-identity
                              [(assoc statement "id" s-id)]
                              attachments))
           bad-params-response))))})

(s/fdef post-response
  :args (s/cat :ctx map?
               :store-statements-ret ::p/store-statements-ret))

(defn post-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [statement-ids
           error] :as lrs-response}]
  (if error
    (error-response ctx error)
    (assoc ctx :response {:status 200
                          :body statement-ids})))

(def handle-post
  {:name ::handle-post
   :enter
   (fn [{auth-identity ::auth/identity
         :keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (let [{?statements :xapi-schema.spec/statements
            ?statement :xapi-schema.spec/statement
            attachments :xapi.statements/attachments} (:xapi ctx)
           statements (or ?statements [?statement])]
       (if (p/statements-resource-async? lrs)
         (a/go
           (post-response ctx
                          (a/<! (lrs/store-statements-async
                                 lrs
                                 auth-identity
                                 statements
                                 attachments))))
         (post-response ctx
                        (lrs/store-statements
                         lrs
                         auth-identity
                         statements
                         attachments)))))})

;; TODO: wrap attachment response
;; TODO: Last modfified https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#requirements-4
;; TODO: wrap ltags
;; TODO: wrap alt req. check

(defn- aconcat
  "Given xs and a chan c, return a new chan with xs at the head and c at the tail"
  [xs c]
  (let [out-c (a/chan (count xs))]
    (a/go-loop [xxs xs]
      (if-let [x (first xxs)]
        (do (a/>! out-c x)
            (recur (rest xxs)))
        (a/pipe c out-c)))
    out-c))

(s/fdef get-response
  :args (s/cat :ctx map?
               :get-statements-ret ::p/get-statements-ret))

(defn get-response
  [{:keys [xapi
           com.yetanalytics/lrs] :as ctx}
   {:keys [error
           statement-result
           statement
           attachments
           etag]}]
  (if error (error-response ctx error)
      (try (assoc ctx
                  :response
                  (if-let [s-data (or statement statement-result)]
                    (if (and (get-in xapi [:xapi.statements.GET.request/params :attachments])
                             (seq attachments))
                      {:status 200
                       :headers (cond-> {"Content-Type" att-resp/content-type}
                                  etag (assoc "etag" etag))
                       :body
                       ;; shim, the protocol will be expected to return this
                       (att-resp/build-multipart-async
                        (a/to-chan (if (some? statement)
                                     (concat (list :statement statement)
                                             (cons :attachments attachments))
                                     (concat (cons :statements
                                                   (:statements statement-result))
                                             (when-let [more (:more statement-result)]
                                               (list :more more))
                                             (cons :attachments attachments)))))}
                      (if statement-result
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (si/lazy-statement-result-async
                                (a/to-chan (concat (cons :statements
                                                         (:statements statement-result))
                                                   (when-let [more (:more statement-result)]
                                                     (list :more more)))))}
                        {:status 200
                         :body s-data}))
                    {:status 404 :body ""}))
           (catch #?(:clj Exception
                     :cljs js/Error) ex
             (error-response ctx ex)))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn [{auth-identity ::auth/identity
         :keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (let [params (get-in ctx [:xapi :xapi.statements.GET.request/params] {})
           ltags (get ctx :xapi/ltags [])]
       (if (p/statements-resource-async? lrs)
         (let [r-chan (lrs/get-statements-async
                       lrs
                       auth-identity
                       params
                       ltags)]
           (a/go
             (let [header (a/<! r-chan)]
               (case header
                 :statement
                 (assoc ctx
                        :response
                        (let [?statement (a/<! r-chan)]
                          (if (map? ?statement)
                            (if (:attachments params)
                              {:status 200
                               :headers {"Content-Type" att-resp/content-type}
                               :body
                               (att-resp/build-multipart-async
                                (aconcat [:statement
                                          ?statement]
                                         r-chan))}
                              {:status 200
                               :body ?statement})
                            {:status 404 :body ""})))
                 :statements
                 (assoc ctx
                        :response
                        (if (:attachments params)
                          {:status 200
                           :headers {"Content-Type" att-resp/content-type}
                           :body
                           (att-resp/build-multipart-async
                            (aconcat [:statements] r-chan))}
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body
                           (si/lazy-statement-result-async
                            (aconcat [:statements] r-chan))}))
                 :error
                 (let [ex (a/<! r-chan)]
                   (assoc ctx :io.pedestal.interceptor.chain/error ex))))))
         (get-response ctx (lrs/get-statements
                            lrs
                            auth-identity
                            params
                            ltags)))))})
