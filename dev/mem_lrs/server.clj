(ns mem-lrs.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [mem-lrs.service :as service]
            [com.yetanalytics.lrs.impl.memory :as lrs-impl :refer [new-lrs]]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server (i/xapi-default-interceptors
                                                 service/service)))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& {:keys [reload-routes?
             lrs]
      :or {reload-routes? true
           lrs (new-lrs {})}}]
  (println "\nCreating your [DEV] server...")
  (-> service/service ;; start with production configuration
      (merge {:env :dev
              ::lrs lrs
              ;; do not block thread that starts web server
              ::server/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::server/routes (if reload-routes?
                                #(route/expand-routes (service/new-routes lrs))
                                (service/new-routes lrs))
              ;; all origins are allowed in dev mode
              ::server/allowed-origins {:creds true :allowed-origins (constantly true)}
              ;; Content Security Policy (CSP) is mostly turned off in dev mode
              ::server/secure-headers {:content-security-policy-settings {:object-src "none"}}})
      ;; Wire up interceptor chains
      ;; server/default-interceptors
      i/xapi-default-interceptors
      server/dev-interceptors
      server/create-server
      server/start))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (server/start runnable-service))

;; If you package the service up as a WAR,
;; some form of the following function sections is required (for io.pedestal.servlet.ClojureVarServlet).

;;(defonce servlet  (atom nil))
;;
;;(defn servlet-init
;;  [_ config]
;;  ;; Initialize your app here.
;;  (reset! servlet  (server/servlet-init service/service nil)))
;;
;;(defn servlet-service
;;  [_ request response]
;;  (server/servlet-service @servlet request response))
;;
;;(defn servlet-destroy
;;  [_]
;;  (server/servlet-destroy @servlet)
;;  (reset! servlet nil))

(comment
  (def lrs (new-lrs {}
                    #_{:init-state
                     (lrs-impl/fixture-state)}))

  (clojure.pprint/pprint (lrs-impl/dump lrs))

  (def s (run-dev :lrs lrs))
  (server/stop s)
  (def s nil)

  (def state (lrs-impl/dump lrs))


  (time (satisfies? lrs-impl/DumpableMemoryLRS lrs))
  (+ 1 11)
  )
