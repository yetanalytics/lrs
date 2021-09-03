; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http
  "Namespace which ties all the pedestal components together in a
  sensible default way to make a full blown application."
  (:require
   [io.pedestal.interceptor :as pedestal.interceptor]
   [macchiato.util.response :as ring-response]))

;; Interceptors
;; ------------
;; We avoid using the macro-versions in here, to avoid complications with AOT.
;; The error you'd see would be something like,
;;   "java.lang.IllegalArgumentException:
;;      No matching ctor found for class io.pedestal.interceptor.helpers$after$fn__6188"
;; Where the macro tries to call a function on 0-arity, but the actual
;; interceptor (already compiled) requires a 2-arity version.

(defn response?
  "A valid response is any map that includes an integer :status
  value."
  [resp]
  (and (map? resp)
       (integer? (:status resp))))

(def not-found
  "An interceptor that returns a 404 when routing failed to resolve a route."
  (pedestal.interceptor/interceptor
   {:name ::not-found
    :leave
    (fn [context]
      (if-not (response? (:response context))
        (do #_(log/meter ::not-found)
          (assoc context :response (ring-response/not-found "Not Found")))
        context))}))

(def json-body
  "Set the Content-Type header to \"application/json\" and convert the body to
  JSON if the body is a collection and a type has not been set."
  (pedestal.interceptor/interceptor
   {:name ::json-body
    :leave (fn [{:keys [response] :as ctx}]
             (assoc ctx :response
                    (let [body (:body response)
                          content-type (get-in response [:headers "Content-Type"])]
                      (if (and (coll? body) (not content-type))
                        (-> response
                            (ring-response/content-type "application/json;charset=UTF-8")
                            (assoc :body (.stringify js/JSON (clj->js body))))
                        response))))}))

;;TODO: Make this a multimethod
(defn interceptor-chain-provider
  [service-map]
  (let [provider (cond
                   (fn? (::chain-provider service-map)) (::chain-provider service-map)
                   :else (throw (ex-info "There was no provider or server type specified.
                                          Unable to create/connect interceptor chain foundation.
                                          Try setting :type to :jetty in your service map."
                                         {:type ::no-provider})))]
    (provider service-map)))

(defn create-provider
  "Creates the base Interceptor Chain provider, connecting a backend to the interceptor
  chain."
  [service-map]
  (-> service-map
      interceptor-chain-provider))

(defn- service-map->server-options
  [service-map]
  (let [server-keys [::host ::port ::join? ::container-options]]
    (into {} (map (fn [[k v]] [(keyword (name k)) v]) (select-keys service-map server-keys)))))

(defn- server-map->service-map
  [server-map]
  (into {} (map (fn [[k v]] [(keyword "io.pedestal.http" (name k)) v]) server-map)))

(defn server
  [service-map]
  (let [{type ::type
         :or {type :jetty}} service-map
        ;; Ensure that if a host arg was supplied, we default to a safe option, "localhost"
        service-map-with-host (if (::host service-map)
                                service-map
                                (assoc service-map ::host "localhost"))
        server-fn (if (fn? type)
                    type
                    (throw (ex-info "Only functions are allowed for service types in js."
                                    {:type ::invalid-type})))
        server-map (server-fn service-map (service-map->server-options service-map-with-host))]
    (merge service-map-with-host (server-map->service-map server-map))))

(defn create-server
  ([service-map]
   (create-server service-map (constantly nil)))
  ([service-map init-fn]
   (init-fn)
   (-> service-map
      create-provider ;; Creates/connects a backend to the interceptor chain
      server)))

(defn start [service-map]
  ((::start-fn service-map))
  service-map)

(defn stop [service-map]
  ((::stop-fn service-map))
  service-map)
