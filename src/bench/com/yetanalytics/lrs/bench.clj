(ns com.yetanalytics.lrs.bench
  "Benchmarking + perf for the LRS lib"
  (:require
   [hato.client :as http]
   [com.yetanalytics.lrs.xapi.statements.timestamp :as ts]
   [com.yetanalytics.datasim.input :as di]
   [com.yetanalytics.datasim.sim :as ds]
   [java-time :as t]
   [clojure.pprint :refer [pprint]])
  (:import
   [java.time Instant]))

(set! *warn-on-reflection* true)

(defonce default-payload-input
  (update (di/from-location
           :input :json "dev-resources/datasim/input/tc3.json")
          :parameters
          #(-> %
               (dissoc :end :from))))

(defonce default-payload
  ;; "static payload derived from DATASIM, will be consumed entirely on use"
  (ds/sim-seq default-payload-input))


(def default-client-opts
  "default options for hato client"
  {:connect-timeout 10000
   :redirect-policy :always
   :authenticator {:user "default"
                   :pass "123456789"}})

(defonce default-client
  ;; "default hato client"
  (http/build-http-client default-client-opts))

(def default-request-options
  {:throw-exceptions? false
   :headers {"x-experience-api-version" "1.0.3"}
   :as :json-strict-string-keys
   :content-type :json})

(defn store-payload-sync
  "Synchronously push the payload to the lrs and prepare a report.
  Prioritize pushing the statements as fast as possible with minimal
  local calculation, as we will get our perf results through stored time."
  [lrs-endpoint
   & {:keys
      [run-id ;; unique string to identify this run of the sim

       payload ;; seq of statements
       payload-input ;; input file for dsim
       payload-input-path ;; path to dsim input file
       payload-parameters ;; dsim param overrides

       size ;; total size of sim
       batch-size ;; POST batch size

       send-ids?
       dry-run? ;; don't try to communicate
       client-options
       request-options
       http-client]
      :or
      {run-id (.toString ^java.util.UUID (java.util.UUID/randomUUID))
       payload-parameters {}
       size 1000
       batch-size 10
       send-ids? false
       dry-run? false
       }
      :as options}]
  (printf "\nSync POST starting at %s\n\nid: %s endpoint: %s\noptions: %s\n"
          (ts/stamp-now) run-id lrs-endpoint options)
  (let [http-client (or
                     ;; user provides http client
                     http-client
                     ;; user provides opts for one
                     (and client-options
                          (http/build-http-client client-options))
                     ;; use the default
                     default-client)
        ;; the payload is just statements
        payload (doall ;; make sure all gen is complete, preflight
                 (map
                  (if send-ids?
                    identity
                    #(dissoc % "id"))
                  ;; obey the size param
                  (take size
                        (or
                         ;; user provides statements
                         payload
                         ;; user provides a payload input
                         (and payload-input
                              (ds/sim-seq (update payload-input
                                                  :parameters
                                                  merge payload-parameters)))
                         ;; user provides a path to a payload input
                         (and payload-input-path
                              (ds/sim-seq (update (di/from-location
                                                   :input :json payload-input-path)
                                                  :parameters
                                                  merge payload-parameters)))
                         ;; user just provides some sim params
                         (and payload-parameters
                              (ds/sim-seq (update default-payload-input
                                                  :parameters
                                                  merge payload-parameters)))
                         ;; use the default
                         default-payload))))
        registrations (into #{} (map #(get-in % ["context" "registration"]) payload))]
    (assert (= size (count payload))
            "Payload is less than desired size! Check DATASIM options")
    ;; We just loop through the statements and POST them in batches.
    (let [;; pull these out for the return
          opts' (merge
                 default-request-options
                 request-options
                 )]
      (loop [batches (partition-all batch-size payload)
             results []]
        (if-let [batch (first batches)]
          (let [opts (merge
                      {:http-client http-client}
                      opts'
                      {:form-params batch})
                {:keys [status
                        body]
                 :as response} (if dry-run?
                                 {:status 200
                                  :body []}
                                 (http/post
                                  (format "%s/statements"
                                          lrs-endpoint)
                                  opts))]
            (if (= 200 status)
              (recur (rest batches)
                     (conj results
                           {:response response
                            :ids body}))
              (throw (ex-info "LRS ERROR!"
                              {:type ::lrs-post-error
                               :request-options opts
                               :response response}))))
          (assoc (reduce (fn [m {:keys [response
                                        ids]}]
                           (-> m
                               (update :ids into ids)
                               (update :responses conj response)
                               (update :request-time +
                                       (:request-time response))))
                         {:responses []
                          :ids []
                          :request-time 0}
                         results)
                 :registrations registrations
                 :run-id run-id
                 :lrs-endpoint lrs-endpoint
                 :http-client http-client
                 :request-options opts'
                 ;; original post options
                 :post-options
                 options))))))

(defn get-payload-sync
  "take a payload post report and any additional options, get statement data for
   analysis"
  [{:keys
    [lrs-endpoint
     run-id

     request-options
     http-client
     responses
     request-time

     ids
     registration]
    :as post-report}
   & {:keys
      [strategy ;; how do we retrieve?
       ]
      :or
      {strategy :get-ids}
      :as options}]
  (printf "\nSync GET starting at %s\n\nid: %s endpoint: %s\noptions: %s\n"
          (ts/stamp-now) run-id lrs-endpoint options)

  (case strategy
    :get-ids
    (loop [ids-to-get ids
           statements []]
      (if-let [id (first ids-to-get)]
        (let [opts (merge
                    {:http-client http-client}
                    request-options
                    {:query-params {:statementId id}})
              {:keys [status
                      body]
               :as response} (http/get
                              (format "%s/statements"
                                      lrs-endpoint)
                              opts)]
          (if (= 200 status)
            (recur (rest ids-to-get)
                   (conj statements body))
            (throw (ex-info "LRS ERROR!"
                            {:type ::lrs-get-error
                             :request-options opts
                             :response response}))))
        statements))))

;; metric functions apply to the sequence of statements
(defmulti metric "multifunction to register metrics on statements"
  (fn [metric-key _ & _]
    metric-key))

;; just a sanity check
(defmethod metric :integrity
  [_ statements]
  {:ascending? (= statements
                  (sort-by #(get % "stored") statements))})

#_(defmethod metric :frequency
  [_ statements & {:keys [per]
                   :or {per :second}}]
  )

(defn report
  "Derive metrics from a "
  [payload-statements & {:keys [metrics]
                         :or {metrics {:integrity {}}}}]
  (reduce-kv (fn [m k v]
               (assoc m
                      k
                      (apply metric k payload-statements (mapcat identity v))))
             {:statements payload-statements}
             metrics))



(comment
  ;; you can bench the in-memory impl using code in this comment
  (require '[mem-lrs.server :as lrss]
           '[io.pedestal.http :as server])


  (defn- run-server!
    "Run and return a stop fn"
    []
    (let [svr (lrss/run-dev)]
      #(server/stop svr)))

  (def ret-report
    (let [stop-svr (run-server!)]
      (try (-> (store-payload-sync "http://localhost:8080/xapi"
                                   :size 100
                                   :http-client dev-client)
               ;; => post report
               get-payload-sync
               ;; => statements
               report
               ;; => {report map}
               )
           (finally
             (stop-svr)))))

  (:integrity ret-report) ;; => {:ascending? true}


  )
