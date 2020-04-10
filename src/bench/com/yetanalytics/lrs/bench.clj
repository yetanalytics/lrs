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
  {:throw-exceptions? false
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
     http-client]
    :or
    {run-id (.toString ^java.util.UUID (java.util.UUID/randomUUID))
     payload-input-path "dev-resources/datasim/input/tc3.json"
     http-client default-client
     size 1000
     batch-size 10
     send-ids? false
     dry-run? false
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
                                              {:http-client http-client})})
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
      (assoc-in ctx [:get :statements] statements))))


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
                               total-request-time)})))

(defmethod metric :frequency ;; metrics based on start, end, stored time
  [_ {{:keys [t-zero t-end]} :post
      {:keys [statements]} :get
      :as ctx}]
  (let [statement-count (count statements)
        ;; we count from t-zero (bench init) to the last stored stamp
        ^Duration span (t/duration t-zero
                                   (-> statements
                                       last
                                       (get "stored")
                                       t/instant))
        per-ms (/ (t/as span
                        :millis)
                  statement-count)]
    (assoc-in ctx
              [:report :frequency]
              {:per {:second (double (* 1000 per-ms))
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
      (try (-> (cond-> {:lrs-endpoint lrs-endpoint
                        :size size
                        :batch-size batch-size
                        :send-ids? send-ids?}
                 input-uri (assoc :payload-input-path input-uri)
                 (and user pass)
                 (assoc :request-options (merge default-request-options
                                                {:basic-auth
                                                 {:user user
                                                  :pass pass}})))

               context-init ;; => context

               store-payload-sync

               get-payload-sync

               report

               ;; output the report
               :report
               pprint)
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

  (def ret-ctx
    (let [stop-svr (run-server!)]
      (try (-> {:lrs-endpoint "http://localhost:8080/xapi"
                :size 100}

               context-init

               store-payload-sync

               get-payload-sync

               report

               )
           (finally
             (stop-svr)))))

  (:report ret-ctx)




  )
