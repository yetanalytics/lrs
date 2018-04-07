(ns com.yetanalytics.lrs.pedestal.routes.statements
  (:require [com.yetanalytics.lrs.protocol.xapi.statements :as statements-proto]))

(defn error-response
  "Define error responses for statement resource errors"
  [exi]
  (let [exd (ex-data exi)]
    (case (:type exd)
      ::statements-proto/invalid-params
      {:status 400
       :body
       {:error
        {:message (.getMessage exi)
         :params (:params exi)}}}
      ::statements-proto/invalid-statements
      {:status 400
       :body
       {:error
        {:message (.getMessage exi)
         :invalid-statements
         (:invalid-statements exd)}}}
      ::statements-proto/invalid-attachments
      {:status 400
       :body
       {:error
        {:message (.getMessage exi)}}}
      ::statements-proto/conflicting-statements
      {:status 409
       :body
       {:error
        {:message (.getMessage exi)
         :conflicting-statements
         (:conflicting-statements exd)
         :extant-statements
         (:extant-statements exd)}}}
      ::statements-proto/invalid-voiding-statements
      {:status 400
       :body
       {:error
        {:message (.getMessage exi)
         :invalid-voiding-statements
         (:invalid-voiding-statements exd)}}}
      (throw exi))))

(def handle-put
  {:name ::handle-put
   :enter
   (fn [ctx]
     (assoc ctx :response
            (let [params (get-in ctx [:request :params])]
              (if-let [s-id (get params "statementId")]
                (let [lrs (get ctx :com.yetanalytics/lrs)
                      statement (get-in ctx [:request :body])
                      attachments (get ctx :xapi/attachments [])]
                  (try (statements-proto/store-statements
                        lrs [(assoc statement "id" s-id)]
                        attachments)
                       {:status 204}
                       (catch clojure.lang.ExceptionInfo exi
                         (error-response exi))))
                {:status 400
                 :body
                 {:error
                  {:message "statementId param required!"
                   :params params}}}))))})

(def handle-post
  {:name ::handle-post
   :enter
   (fn [ctx]
     (assoc ctx :response
            (let [lrs (get ctx :com.yetanalytics/lrs)
                  statements (get-in ctx [:request :body] [])
                  attachments (get ctx :xapi/attachments [])]
              (try
                {:status 200
                 :body (statements-proto/store-statements
                        lrs (if (map? statements)
                              [statements]
                              statements)
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
                  params (get-in ctx [:request :params] {})
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
