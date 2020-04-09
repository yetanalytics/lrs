(ns com.yetanalytics.lrs.bench
  "Benchmarking + perf for the LRS lib"
  (:gen-class)
  (:require
   [hato.client :as http]
   [com.yetanalytics.datasim.input :as di]
   [com.yetanalytics.datasim.sim :as ds]
   [java-time :as t]
   [clojure.core.matrix.stats :as stats]
   [clojure.tools.cli :as cli]
   [clojure.pprint :refer [pprint]])
  (:import
   [java.time Duration Instant]))

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
   #_:authenticator #_{:user "default"
                       :pass "123456789"}
   })

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
   {:keys
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
                 )
          ^Instant t-zero (Instant/now)]
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
          (let [^Instant t-end (Instant/now)]
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
                   options
                   :t-zero t-zero
                   :t-end t-end
                   :size size
                   :batch-size batch-size)))))))

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
     registration
     t-zero
     t-end]
    :as post-report}
   & {:keys
      [strategy ;; how do we retrieve?
       ]
      :or
      {strategy :get-ids}
      :as options}]
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
        {:statements statements
         :post-report post-report
         :t-zero t-zero
         :t-end t-end}))))

;; metric functions apply to the sequence of statements
(defmulti metric "multifunction to register metrics on statements"
  (fn [metric-key _ & _]
    metric-key))

;; just a sanity check and some simple results
(defmethod metric :misc
  [_ {:keys [statements
             t-zero
             t-end]}]
  (let [first-stored (t/instant (get (first statements) "stored"))
        last-stored (t/instant (get (last statements) "stored"))]
    {:count (count statements)
     :ascending? (= statements
                    (sort-by #(get % "stored") statements))
     :first-stored first-stored
     :last-stored last-stored
     ;; is t-zero before first stored before last-stored before t-end
     :sane-stored?
     (= [t-zero first-stored last-stored t-end]
        (sort [t-zero first-stored last-stored t-end]))}))

(defmethod metric :post-perf
  [_ {{:keys [size
              batch-size
              responses
              t-zero
              t-end]} :post-report}]
  ;; average POST request throughput
  (let [response-count (count responses)
        request-times (map :request-time responses)
        total-request-time (reduce + request-times)
        ^Duration span (t/duration t-zero t-end)]
    {:post-stats {:mean (double (stats/mean request-times))
                  :stddev (stats/sd request-times)
                  :min (apply min request-times)
                  :max (apply max request-times)
                  :variance (stats/variance request-times)
                  :sum-of-squares (stats/sum-of-squares request-times)}
     :statement-per-ms-avg (double (stats/mean (map
                                                #(/ %
                                                    batch-size)
                                                request-times)))
     :total-request-time total-request-time
     :overhead-ms (- (t/as span
                           :millis)
                     total-request-time)}))

(defmethod metric :frequency ;; metrics based on start, end, stored time
  [_ {:keys [statements
             t-zero
             t-end]}]
  (let [statement-count (count statements)
        ^Duration span (t/duration t-zero t-end)
        per-ms (/ (t/as span
                        :millis)
                  statement-count)]
    {:per {:second (double (* 1000 per-ms))
           :ms (double per-ms)}}))

(defn report
  "Derive metrics from a seq of statements"
  [get-report & {:keys [metrics]
                 :or {metrics {:misc {}
                               :post-perf {}
                               :frequency {}}}}]
  (assert (-> get-report
              :statements
              count
              (> 0)) "No statements returned. No report.")
  (reduce-kv (fn [m k v]
               (assoc m
                      k
                      (apply metric k get-report (mapcat identity v))))
             {}
             metrics))

;; Call from the command line
(def cli-options
  [["-s" "--size LONG" "Size"
    :id :size
    :parse-fn #(Long/parseLong %)
    :default 1000
    :desc "The total number of statements to send"]
   ["-b" "--batch-size LONG" "Statements per batch"
    :id :batch-size
    :parse-fn #(Long/parseLong %)
    :default 10
    :desc "The batch size to use for posting statements"]
   ["-i" "--input URI" "Combined DATASIM Simulation input"
    :id :input-uri
    :desc "The location of a JSON file containing a combined simulation input spec."]
   ["-u" "--user STRING" "LRS User"
    :id :user
    :desc "HTTP Basic Auth user"]
   ["-p" "--pass STRING" "LRS Password"
    :id :pass
    :desc "HTTP Basic Auth password"]
   ["--send-ids BOOLEAN" "Send IDs?"
    :id :send-ids?
    :parse-fn #(Boolean/parseBoolean %)
    :default false
    :desc "If true, will send IDs with statements, which will affect LRS performance."]
   ["-h" "--help"]])

(defn bail!
  "Print error messages to std error and exit."
  [errors & {:keys [status]
             :or {status 1}}]
  (binding [*out* *err*]
    (doseq [error errors]
      (println (if (string? error)
                 error
                 (ex-message error))))
    (flush)
    (System/exit status)))

(defn -main [lrs-endpoint & args]
  (let [{:keys [arguments
                summary
                errors]
         :as parsed-opts
         {:keys [size batch-size
                 input-uri
                 send-ids?
                 user pass]} :options} (cli/parse-opts args cli-options)]
    (if (not-empty errors)
      (bail! errors)
      (try (-> (store-payload-sync lrs-endpoint
                                   (cond-> {:size size
                                            :batch-size batch-size
                                            :send-ids? send-ids?}
                                     input-uri (assoc :payload-input-path input-uri)
                                     (and user pass)
                                     (assoc :request-options (merge default-request-options
                                                                    {:basic-auth
                                                                     {:user user
                                                                      :pass pass}}))))
               ;; => post report
               get-payload-sync
               ;; => get report
               report
               ;; => {report map}
               pprint
               )
           (flush)
           (System/exit 0)
           (catch Exception ex
             (bail! [ex]))))))





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
                                   {:size 100
                                    })
               ;; => post report
               get-payload-sync
               ;; => get report
               report
               ;; => {report map}
               )
           (finally
             (stop-svr)))))

  (:frequency ret-report)


  )
