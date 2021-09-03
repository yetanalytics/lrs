;; Copyright 2013 Relevance, Inc.
;; Copyright 2014-2019 Cognitect, Inc.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;;
;; You must not remove this notice, or any other, from this software.
;;
;; Adapted from https://github.com/pedestal/pedestal/blob/master/service/src/io/pedestal/http/impl/servlet_interceptor.clj

(ns com.yetanalytics.node-chain-provider
  (:require [cljs.nodejs :as node]
            [fs]
            [macchiato.server :as server]
            [macchiato.http :as mhttp]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a :include-macros true]
            [concat-stream]))

(extend-protocol mhttp/IHTTPResponseWriter
  cljs.core.async.impl.channels/ManyToManyChannel
  (-write-response [chan node-server-response raise]
    (a/go-loop []
      (if-let [datum (a/<! chan)]
        (do (try (.write node-server-response datum)
                 (catch js/TypeError e
                   (if (object? datum)
                     ;; TODO: allow these to stream
                     (.write node-server-response (.readFileSync fs (.-name datum)))
                     (throw (ex-info "Could not write datum!"
                                     {:type ::datum-write-fail
                                      :datum datum
                                      :resp node-server-response}
                                     e)))))
            (recur))
        (.end node-server-response)))))

(defn- terminator-inject
  [context]
  (chain/terminate-when context :response))

(def terminator-injector
  "An interceptor which causes a interceptor to terminate when one of
  the interceptors produces a response, as defined by
  ring.util.response/response?"
  (interceptor/interceptor
   {:name ::terminator-injector
    :enter terminator-inject}))

;; Noops in our impl
(defn- enter-stylobate
  [{:keys [request] :as ctx}]
  (assert request "Macchiato stylobate expects a request to be added")
  ctx)

(defn- leave-stylobate
  [context]
  context)

(defn- error-stylobate
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [{:keys [servlet-response] :as context} exception]
  (.error js/console
          (str {:msg "error-stylobate triggered"
                :exception exception
                :context context}))
  (leave-stylobate context))

(def stylobate
  "An interceptor which creates favorable pre-conditions for further
  io.pedestal.interceptors, and handles all post-conditions for
  processing an interceptor chain. It expects a context map
  with :servlet-request, :servlet-response, and :servlet keys.
  After entering this interceptor, the context will contain a new
  key :request, the value will be a request map adhering to the Ring
  specification[1].
  This interceptor supports asynchronous responses as defined in the
  Java Servlet Specification[2] version 3.0. On leaving this
  interceptor, if the servlet request has been set asynchronous, all
  asynchronous resources will be closed. Pausing this interceptor will
  inform the servlet container that the response will be delivered
  asynchronously.
  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will log the error but not communicate
  any details to the client.
  [1]: https://github.com/ring-clojure/ring/blob/master/SPEC
  [2]: http://jcp.org/aboutJava/communityprocess/final/jsr315/index.html"

  (interceptor/interceptor
   {:name ::stylobate
    :enter enter-stylobate
    :leave leave-stylobate
    :error error-stylobate}))

(defn- ensure-body
  "Make sure the body is at least an empty string, otherwise node
  Doesn't seem to like it"
  [resp]
  (assoc resp :body (or (:body resp) "")))

(defn- leave-ring-response
  [{{body :body :as response} :response
    response-fn :node/response-fn
    raise-fn :node/raise-fn
    :as context}]
  (cond
    (nil? response) (do (raise-fn (ex-info "No response."
                                           {:type ::no-response}))
                        context)
    :else (do (response-fn (ensure-body response))
             context)))

(defn- error-ring-response
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [{:keys [node/raise-fn] :as context} exception]
  (.error js/console (str {:msg "error-ring-response triggered"
                       :exception exception
                       :context context}))
  (raise-fn "Internal server error: exception")
  context)

(def ring-response
  "An interceptor which transmits a Ring specified response map to an
  HTTP response.
  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will set the HTTP response status code
  to 500 with a generic error message. Also, if later interceptors
  fail to furnish the context with a :response map, this interceptor
  will set the HTTP response to a 500 error."
  (interceptor/interceptor {:name ::ring-response
                            :leave leave-ring-response
                            :error error-ring-response}))

(defn macchiato-provider
  "Given a service-map,
  provide all necessary functionality to execute the interceptor chain,
  including extracting and packaging the base :request into context.
  These functions/objects are added back into the service map for use within
  the server-fn.
  See io.pedestal.http.impl.servlet-interceptor.clj as an example.
  Interceptor chains:
   * Terminate based on the list of :terminators in the context.
   * Call the list of functions in :enter-async when going async.  Use these to toggle async mode on the container
   * Will use the fn at :async? to determine if the chain has been operating in async mode (so the container can handle on the outbound)"
  [service-map]
  (let [interceptors (:io.pedestal.http/interceptors service-map [])
        default-context (get-in service-map [:io.pedestal.http/container-options :default-context] {})
        body-processor (get-in service-map
                               [:io.pedestal.http/container-options :body-processor]
                               )]
    (assoc service-map
           ::handler
           (fn [req ;; request map
                res ;; response callback fn
                raise ;; error callback fn
                ]
             (let [req (merge {:path-info (:uri req)}
                              (-> req
                                  ;; Make sure there is something there for params
                                  (assoc :params (or (:params req) {}))
                                  ;; Make sure content-length is a number
                                  (update :content-length js/parseInt)))
                   context (merge default-context
                                  {:request req
                                   :node/response-fn res
                                   :node/raise-fn raise})]
               (chain/execute context (concat [terminator-injector
                                               stylobate
                                               ring-response]
                                              interceptors)))))))

(defn macchiato-server-fn
  "Given a service map (with interceptor provider established) and a server-opts map,
  Return a map of :server, :start-fn, and :stop-fn.
  Both functions are 0-arity"
  [service-map server-opts]
  (let [handler (::handler service-map)
        {:keys [host port join?]
         :or {host "127.0.0.1"
              port 8080
              ;; join? false
              }} server-opts
        server (delay (server/start
                       {:handler handler
                        :host host
                        :port port
                        :on-success #(.log js/console
                                           (str "macchiato server started on " host ":" port))}))]
    {:server server
     :start-fn (fn []
                 @server)
     :stop-fn (fn []
                (.close @server (fn [_] (.log js/console "macchiato server shutdown")))
                server)}))
