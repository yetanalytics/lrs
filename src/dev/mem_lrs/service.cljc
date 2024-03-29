(ns mem-lrs.service
  (:require [io.pedestal.http :as http]
            [com.yetanalytics.lrs.impl.memory :as lrs-impl :refer [new-lrs]]
            [com.yetanalytics.lrs.pedestal.routes :refer [build]]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            #?(:cljs [com.yetanalytics.node-chain-provider :as provider])))

(defn new-routes [lrs]
  (build {:lrs lrs
          :wrap-interceptors [i/error-interceptor]}))

;; Tabular routes + default LRS
(def default-lrs
  (new-lrs {}))

(def routes
  (new-routes default-lrs))

;; Consumed by mem-lrs.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service
  {:env :prod
   ;; You can bring your own non-default interceptors. Make
   ;; sure you include routing and set it up right for
   ;; dev-mode. If you do, many other keys for configuring
   ;; default interceptors will be ignored.
   ;; ::http/interceptors []
   ::http/routes routes
   ;; ::http/method-param-name :method

   ;; Uncomment next line to enable CORS support, add string(s) specifying
   ;; scheme, host and port for allowed source(s):
   ;;
   ;; "http://localhost:8080"
   ;;
   ;; ::http/allowed-origins ["scheme://host:port"]

   ;; Tune the Secure Headers and specifically the Content Security Policy
   ;; appropriate to your service/application.
   ;;
   ;; For more information, see: https://content-security-policy.com/
   ;;   See also: https://github.com/pedestal/pedestal/issues/499
  ;;  ::http/secure-headers
  ;;  {:content-security-policy-settings
  ;;   {:object-src      "'none'"
  ;;    :script-src      "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
  ;;    :frame-ancestors "'none'"}}

   ;; Root for resource interceptor that is available by default.
   #?@(:clj [::http/resource-path "/public"])

   ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
   ;;  This can also be your own chain provider/server-fn -- http://pedestal.io/reference/architecture-overview#_chain_provider
   ::http/type #?(:clj :jetty
                  :cljs provider/macchiato-server-fn) ;; :immutant ;; :jetty
   #?@(:cljs [::http/chain-provider provider/macchiato-provider])
   
   ;;::http/host "localhost"
   ::http/port 8080
   
   ;; Options to pass to the container (Jetty)
   ::http/container-options {:h2c? true
                             :h2? false
                             ; :keystore "test/hp/keystore.jks"
                             ; :key-password "password"
                             ; :ssl-port 8443
                             :ssl? false}})
