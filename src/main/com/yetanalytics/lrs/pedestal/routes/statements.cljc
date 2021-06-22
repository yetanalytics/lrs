(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs :as lrs]
            [com.yetanalytics.lrs.protocol :as p]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements.attachment.response
             :as att-resp]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as si]
            [clojure.core.async :as a :include-macros true]
            [com.yetanalytics.lrs.auth :as auth]
            [clojure.spec.alpha :as s :include-macros true]
            [com.yetanalytics.lrs.pedestal.routes.statements.html :as html]))

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

(defn- accept-html?
  "Did the user request html?"
  [ctx]
  (some-> ctx
          ^String (get-in [:request
                           :headers
                           "accept"])
   (.startsWith "text/html")))


(s/fdef get-response-sync
  :args (s/cat :ctx map?
               :get-statements-ret ::p/get-statements-ret))

(defn get-response-sync
  [{:keys [xapi
           com.yetanalytics/lrs]
    :as ctx}
   {:keys [error
           statement-result
           statement
           attachments
           etag]}]
  (if error (error-response ctx error)
      (try (assoc ctx
                  :response
                  (if (or statement statement-result)
                    (if (get-in xapi [:xapi.statements.GET.request/params :attachments])
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
                      (if (accept-html? ctx)
                        (if statement-result
                          (html/statements-response
                           ctx
                           statement-result)
                          (html/statement-response
                           ctx
                           statement))
                        (if statement-result
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (si/lazy-statement-result-async
                                  (a/to-chan (concat (cons :statements
                                                           (:statements statement-result))
                                                     (when-let [more (:more statement-result)]
                                                       (list :more more)))))}
                          ;; otherwise, statement assumed
                          {:status 200
                           ;; TODO: if content-type headers get set here the body
                           ;; is not coerced
                           :body statement})))
                    ;; not found
                    {:status 404 :body ""}))
           (catch #?(:clj Exception
                     :cljs js/Error) ex
             (error-response ctx ex)))))

(defn get-response-async
  [{{params :xapi.statements.GET.request/params} :xapi
    :as ctx}
   r-chan]
  (a/go
    (merge
     ctx
     (let [header (a/<! r-chan)]
       (if (= :error header)
         {:io.pedestal.interceptor.chain/error
          (a/<! r-chan)}
         {:response
          (case header
             :statement
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
                 {:status 404 :body ""}))
             :statements
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
                 (aconcat [:statements] r-chan))}))})))))

(def handle-get
  {:name ::handle-get
   :enter
   (fn [{auth-identity ::auth/identity
         :keys [xapi
                com.yetanalytics/lrs] :as ctx}]
     (let [params (get-in ctx [:xapi :xapi.statements.GET.request/params] {})
           ltags (get ctx :xapi/ltags [])]
       (if (p/statements-resource-async? lrs)
         (get-response-async
          ctx
          (lrs/get-statements-async
           lrs
           auth-identity
           params
           ltags))
         (get-response-sync
          ctx
          (lrs/get-statements
           lrs
           auth-identity
           params
           ltags)))))})
