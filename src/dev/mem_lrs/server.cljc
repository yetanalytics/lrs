(ns mem-lrs.server
  #?(:clj (:gen-class)) ; for -main method in uberjar
  (:require
   [com.yetanalytics.lrs :as lrs]
   #?(:cljs [cljs.nodejs :as node])
   [io.pedestal.http :as server]
   [io.pedestal.http.route :as route]
   [mem-lrs.service :as service]
   [com.yetanalytics.lrs.impl.memory :as lrs-impl :refer [new-lrs]]
   [com.yetanalytics.lrs.pedestal.interceptor :as i]
   [#?(:clj io.pedestal.log
       :cljs com.yetanalytics.lrs.util.log) :as log]
   [clojure.spec.test.alpha :as stest :include-macros true]))

#?(:clj (set! *warn-on-reflection* true))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server (i/xapi-default-interceptors
                                                 service/service)))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& {:keys [reload-routes?
             lrs
             mode]
      :or {reload-routes? true
           mode :both}}]

  (let [lrs (or lrs
                (new-lrs {:mode mode}))]
    (log/info :msg "Creating your [DEV] server..."
              :mode mode)
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
                ;; ::server/secure-headers {:content-security-policy-settings {:object-src "none"}}
                })
        ;; Wire up interceptor chains
        ;; server/default-interceptors
        i/xapi-default-interceptors
        ;; server/dev-interceptors
        server/create-server
        server/start)))

(defn ^:export -main
  "The entry-point for 'lein run'"
  [& [?mode]]
  (log/info :msg "Instrumenting com.yetanalytics.lrs fns"
            :fns (stest/instrument
                  `[lrs/get-about
                    lrs/get-about-async
                    lrs/set-document
                    lrs/set-document-async
                    lrs/get-document
                    lrs/get-document-async
                    lrs/get-document-ids
                    lrs/get-document-ids-async
                    lrs/delete-document
                    lrs/delete-document-async
                    lrs/delete-documents
                    lrs/delete-documents-async
                    lrs/get-activity
                    lrs/get-activity-async
                    lrs/get-person
                    lrs/get-person-async
                    lrs/store-statements
                    lrs/store-statements-async
                    lrs/get-statements
                    lrs/get-statements-async
                    lrs/consistent-through
                    lrs/consistent-through-async
                    lrs/authenticate
                    lrs/authenticate-async
                    lrs/authorize
                    lrs/authorize-async

                    ;; response handling
                    com.yetanalytics.lrs.pedestal.routes.about/get-response
                    com.yetanalytics.lrs.pedestal.routes.activities/get-response
                    com.yetanalytics.lrs.pedestal.routes.agents/get-response
                    com.yetanalytics.lrs.pedestal.routes.documents/put-response
                    com.yetanalytics.lrs.pedestal.routes.documents/post-response
                    com.yetanalytics.lrs.pedestal.routes.documents/get-single-response
                    com.yetanalytics.lrs.pedestal.routes.documents/get-multiple-response
                    com.yetanalytics.lrs.pedestal.routes.documents/delete-response
                    com.yetanalytics.lrs.pedestal.routes.statements/put-response
                    com.yetanalytics.lrs.pedestal.routes.statements/post-response
                    com.yetanalytics.lrs.pedestal.routes.statements/get-response
                    ]))
  (run-dev :reload-routes? false
           :mode (or
                  (some-> ?mode keyword)
                  :both)))

#?(:cljs (set! *main-cli-fn* -main))
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
  (def lrs (new-lrs {}))

  (clojure.pprint/pprint (lrs-impl/dump lrs))

  (def s (run-dev :lrs lrs))
  (server/stop s)
  (def s nil)
  (-main)
  (com.yetanalytics.lrs/get-statements lrs {:limit 1})
  )
