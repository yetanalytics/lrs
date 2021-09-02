(ns com.yetanalytics.lrs.bench
  "Benchmarking + perf for the LRS lib"
  (:gen-class)
  (:require
   [com.yetanalytics.datasim.input :as di]
   [com.yetanalytics.datasim.sim :as ds]
   [com.yetanalytics.lrs.bench.options :as options]
   [com.yetanalytics.lrs.bench.post :as post]
   [com.yetanalytics.lrs.bench.get :as gget]
   [clojure.spec.alpha :as s]
   [xapi-schema.spec :as xs]
   [clojure.core.async :as a]
   [hato.client :as http]
   [java-time :as t]
   [clojure.core.matrix.stats :as stats]
   [clojure.tools.cli :as cli]
   [clojure.pprint :refer [pprint]])
  (:import
   [java.time Duration Instant]
   [java.net.http HttpClient]))

(set! *warn-on-reflection* true)

(def default-client-opts
  "default options for hato client"
  {:connect-timeout 10000
   :redirect-policy :always})

(defonce ^HttpClient default-client
  ;; "default hato client"
  (http/build-http-client default-client-opts))

(def default-request-options
  {:version :http-1.1
   :throw-exceptions? false
   :headers {"x-experience-api-version" "1.0.3"}
   :as :json-strict-string-keys
   :content-type :json})


;; ::options => ::context
(s/def ::options
  options/spec)

;; Post result
(s/def ::post
  post/spec)

(s/def ::get
  gget/spec)

(s/def ::report
  ;; Unspec'd for now, in flux
  map?)

(s/def ::registrations
  (s/every :context/registration))

(s/def ::payload-count
  pos-int?)

(s/def ::payload ;; payload stored in context
  ::xs/statements)

(s/def ::context
  (s/keys ::req-un [::options
                    ;; and after init
                    ::payload ::registrations ::payload-count]
          ::opt-un [::post ;; after post
                    ::get ;; after retrieval
                    ::report ;; complete
                    ]))

(s/fdef context-init
  :args (s/cat :options ::options)
  :ret ::context)

(defn context-init
  "Initialize client and generate payload"
  [{:keys
    [lrs-endpoint
     run-id ;; unique string to identify this run of the sim

     payload ;; seq of statements
     payload-input-path ;; path to dsim input file

     size ;; total size of sim
     batch-size ;; POST batch size

     send-ids?
     dry-run? ;; don't try to communicate
     request-options
     http-client
     parallelism]
    :or
    {run-id (.toString ^java.util.UUID (java.util.UUID/randomUUID))
     payload-input-path "dev-resources/datasim/input/tc3.json"
     http-client default-client
     size 1000
     batch-size 10
     send-ids? false
     dry-run? false
     parallelism 1
     }
    :as options}]
  (let [;; the payload is just statements
        payload (try (doall ;; make sure all gen is complete, preflight
                      (map
                       (if send-ids?
                         identity
                         #(dissoc % "id"))
                       ;; obey the size param
                       (take size
                             (or
                              ;; user provides statements
                              payload
                              ;; user provides a path to a dsim payload input
                              (and payload-input-path
                                   (ds/sim-seq (di/from-location
                                                :input :json payload-input-path)))
                              ;; use the default
                              (throw (ex-info "No payload!"
                                              {:type ::no-payload
                                               :options options}))))))
                     (catch Exception ex
                       (throw (ex-info "Payload error!"
                                       {:type ::payload-error
                                        :options options}
                                       ex))))
        registrations (into #{} (map #(get-in % ["context" "registration"]) payload))
        payload-count (count payload)]
    (assert (< 0 payload-count)
            "Payload must have at least one statement")
    (assert (= size payload-count)
            "Payload is less than desired size! Check DATASIM options")
    {:options (merge options
                     ;; set by :or
                     {:run-id run-id
                      :http-client http-client
                      :size size
                      :batch-size batch-size
                      :send-ids? send-ids?
                      :dry-run? dry-run?
                      :request-options (merge default-request-options
                                              request-options
                                              {:http-client http-client})
                      :parallelism parallelism})
     :payload payload
     :registrations registrations
     :payload-count payload-count}))

(s/fdef store-payload-sync
  :args (s/cat :ctx ::context)
  :ret ::context)

(defn store-payload-sync
  "Synchronously push the payload to the lrs and prepare a report.
  Prioritize pushing the statements as fast as possible with minimal
  local calculation, as we will get our perf results through stored time."
  [{{:keys
    [lrs-endpoint
     run-id ;; unique string to identify this run of the sim

     size ;; total size of sim
     batch-size ;; POST batch size

     dry-run? ;; don't try to communicate
     request-options]
     :as options} :options
    :keys [payload registrations payload-count]
    :as ctx}]
  ;; We just loop through the statements and POST them in batches.
  (let [^Instant t-zero (Instant/now)]
    (loop [batches (partition-all batch-size payload)
           responses []]
      (if-let [batch (first batches)]
        (let [opts (merge
                    request-options
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
                   (conj responses
                         response))
            (throw (ex-info "LRS ERROR!"
                            {:type ::lrs-post-error
                             :request-options opts
                             :response response}))))
        (let [^Instant t-end (Instant/now)]
          (assoc ctx
                 :post
                 {:responses responses
                  :ids (into []
                             (mapcat :body responses))
                  :t-zero t-zero
                  :t-end t-end}))))))

;; Async benchmark ops are wrapped in blocking functions
;; so async on the inside only

(s/fdef store-payload-async
  :args (s/cat :ctx ::context)
  :ret ::context)

(defn- post-af
  [req chan]
  (http/request (merge req
                       {:method :post
                        :async? true})
                (fn [{:keys [status]
                      :as resp}]
                  (let [thread-name (.getName (Thread/currentThread))]
                    (a/go (a/>! chan (assoc resp
                                            :thread-name
                                            thread-name))
                          (a/close! chan))))
                (fn [ex]
                  (a/go (a/>! chan ex)
                        (a/close! chan)))))

(defn store-payload-async
  "Asynchronously push the payload to the lrs and prepare a report.
  Concurrency subject to ::options/parallelsim"
  [{{:keys
    [lrs-endpoint
     run-id ;; unique string to identify this run of the sim

     size ;; total size of sim
     batch-size ;; POST batch size

     dry-run? ;; don't try to communicate
     request-options
     parallelism]
     :as options} :options
    :keys [payload registrations payload-count]
    :as ctx}]
  ;; We just loop through the statements and POST them in batches.
  (let [requests (mapv (fn [batch]
                         (merge request-options
                                {:url (format "%s/statements"
                                              lrs-endpoint)
                                 :form-params batch}))
                       (partition-all batch-size payload))
        ;; enter the async zone
        req-chan (a/to-chan requests)
        resp-chan (a/chan
                   ;; buffer enough for all results
                   (count requests))
        ;; init time
        ^Instant t-zero (Instant/now)
        ;; do the work and check after
        ^Instant t-end (do
                         (a/<!! (a/pipeline-async parallelism resp-chan post-af req-chan))
                         (Instant/now))
        responses (a/<!! (a/into [] resp-chan))
        ;; leave the async zone

        ?errors (not-empty (remove #(= (:status %) 200) responses))]
    (if-not ?errors
      (assoc ctx
             :post
             {:responses responses
              :ids (into []
                         (mapcat :body responses))
              :t-zero t-zero
              :t-end t-end})
      (throw (ex-info "LRS ERROR!"
                      {:type ::lrs-post-error
                       :response (first ?errors)})))))


(s/fdef get-payload-sync
  :args (s/cat :ctx ::context)
  :ret ::context)

(defn get-payload-sync
  "take a payload post report and any additional options, get statement data for
   analysis"
  [{{:keys
     [lrs-endpoint
      run-id

      size
      batch-size

      dry-run?
      request-options]
     :as options} :options
    {:keys [responses
            ids
            t-zero
            t-end]} :post
    :keys [payload registrations payload-count]
    :as ctx}]
  (loop [ids-to-get ids
         statements []]
    (if-let [id (first ids-to-get)]
      (let [opts (merge
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
      (assoc-in ctx [:get :statements]
                ;; sort the statements, ids may be out of order because async
                (sort-by #(get % "stored")
                         statements)))))


(s/fdef metric
  :args (s/cat :ctx ::context)
  :ret ::context)

;; metric functions apply to the sequence of statements
(defmulti metric "multifunction to register metrics on statements"
  (fn [metric-key _]
    metric-key))

;; just a sanity check and some simple results
(defmethod metric :misc
  [_ {{:keys [size batch-size]} :options
      {:keys [t-zero t-end]} :post
      {:keys [statements]} :get
      :as ctx}]
  (let [first-stored (t/instant (get (first statements) "stored"))
        last-stored (t/instant (get (last statements) "stored"))]
    (assert (= statements (sort-by #(get % "stored") statements))
            "retrieved statements should be in order")
    (assert (= [t-zero first-stored last-stored t-end]
               (sort [t-zero first-stored last-stored t-end]))
            "retrieved statements should be stored during post ops"
            )
    (assoc-in ctx
              [:report :misc]
              {:count (count statements)
               :batch-size batch-size
               :ascending? (= statements
                              (sort-by #(get % "stored") statements))
               :first-stored first-stored
               :last-stored last-stored
               ;; is t-zero before first stored before last-stored before t-end
               :sane-stored?
               (= [t-zero first-stored last-stored t-end]
                  (sort [t-zero first-stored last-stored t-end]))})))

(defmethod metric :post-perf
  [_ {{:keys [size batch-size]} :options
      {:keys [responses t-zero t-end]} :post
      :as ctx}]
  ;; average POST request throughput
  (let [response-count (count responses)
        request-times (map :request-time responses)
        total-request-time (reduce + request-times)
        ^Duration span (t/duration t-zero t-end)]
    (assoc-in ctx
              [:report :post-perf]
              {:response-count response-count
               :post-stats {:mean (double (stats/mean request-times))
                            :stddev (stats/sd request-times)
                            :min (apply min request-times)
                            :max (apply max request-times)
                            :variance (stats/variance request-times)
                            :sum-of-squares (stats/sum-of-squares request-times)}
               :statement-per-ms-avg (double (stats/mean (map
                                                          #(/ %
                                                              batch-size)
                                                          request-times)))
               ;; total time of all (possibly concurrent) reqs
               :total-request-time total-request-time

               ;; from t-zero to t-end
               :total-request-span (t/as span
                                         :millis)

               ;; thread names, for debug
               :thread-names (mapv :thread-name responses)
               })))

(defmethod metric :frequency ;; metrics based on start, end, stored time
  [_ {{:keys [t-zero t-end]} :post
      {:keys [statements]} :get
      :as ctx}]
  (let [statement-count (count statements)
        storeds (map (comp t/instant #(get % "stored"))
                     statements)
        ;; not sure if span should be t-zero to last stored or t-end
        ^Duration span (t/duration t-zero
                                   t-end)
        span-ms (t/as span
                      :millis)
        per-ms (/ statement-count
                  span-ms
                  )]
    (assoc-in ctx
              [:report :frequency]
              {:span-ms span-ms
               :per {:second (double (* 1000 per-ms))
                     :ms (double per-ms)}})))

(defn report
  "Derive metrics from a seq of statements"
  [{{:keys [size batch-size]} :options
    {:keys [t-zero t-end]} :post
    {:keys [statements]} :get
    :as ctx}]
  (assert (not-empty statements) "No statements returned. No report.")
  (->> ctx
       (metric :misc)
       (metric :post-perf)
       (metric :frequency)))

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
   ["-d" "--send-ids BOOLEAN" "Send IDs?"
    :id :send-ids?
    :parse-fn #(Boolean/parseBoolean %)
    :default false
    :desc "If true, will send IDs with statements, which will affect LRS performance."]
   ["-c" "--concurrency LONG" "Async concurrency"
    :id :parallelism
    :parse-fn #(Long/parseLong %)
    :default 8
    :desc "Async POST concurrency"]
   ["-f" "--force-sync BOOLEAN" "Force sync ops?"
    :id :force-sync?
    :parse-fn #(Boolean/parseBoolean %)
    :default false
    :desc "If true, will synchronously post to the LRS"]
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
                 user pass
                 parallelism
                 force-sync?]} :options} (cli/parse-opts args cli-options)]
    (let [store-fn (if force-sync?
                     store-payload-sync
                     store-payload-async)]
      (if (not-empty errors)
        (bail! errors)
        (do
          (-> (cond-> {:lrs-endpoint lrs-endpoint
                       :size size
                       :batch-size batch-size
                       :send-ids? send-ids?
                       :parallelism parallelism}
                input-uri (assoc :payload-input-path input-uri)
                (and user pass)
                (assoc :request-options (merge default-request-options
                                               {:basic-auth
                                                {:user user
                                                 :pass pass}})))

              context-init ;; => context

              store-fn

              get-payload-sync

              report

              ;; output the report
              (select-keys [:report :options])
              pprint)
          (flush)
          (System/exit 0))))))





(comment
  ;; you can bench the in-memory impl using code in this comment
  (require '[mem-lrs.server :as lrss]
           '[io.pedestal.http :as server])


  (defn- run-server!
    "Run and return a stop fn"
    []
    (let [svr (lrss/run-dev)]
      #(server/stop svr)))

  (def ret-ctx
    (let [stop-svr (run-server!)
          send-stuff #(time (store-payload-async %))]
      (try
        (let [ctx (-> {:lrs-endpoint "http://localhost:8080/xapi"
                       :size 1000
                       :batch-size 100
                       :parallelism 8
                       }

                      context-init


                      store-payload-async

                      get-payload-sync

                      report


                      )]
          (pprint (select-keys ctx [:options :report]))
          ctx
          )
           (finally
             (stop-svr)))))

  (get-in ret-ctx [:report :post-perf])

  (get-in ret-ctx [:report :misc])



  )
