(defproject com.yetanalytics/lrs "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.yetanalytics/xapi-schema "1.0.0-alpha12-SNAPSHOT"]
                 [io.pedestal/pedestal.service "0.5.3"]
                 [buddy/buddy-sign "2.2.0"]
                 [org.apache.commons/commons-email "1.5"]
                 [commons-fileupload/commons-fileupload "1.3.3"]]
  :profiles {:dev
             {:source-paths ["src" "dev"]
              :repl-options {:init-ns user}
              :dependencies [[org.clojure/test.check "0.10.0-alpha2"]
                             [io.pedestal/pedestal.service "0.5.3"]
                             [io.pedestal/pedestal.jetty "0.5.3"]
                             [ch.qos.logback/logback-classic "1.1.8" :exclusions [org.slf4j/slf4j-api]]
                             [org.slf4j/jul-to-slf4j "1.7.22"]
                             [org.slf4j/jcl-over-slf4j "1.7.22"]
                             [org.slf4j/log4j-over-slf4j "1.7.22"]
                             [io.pedestal/pedestal.service-tools "0.5.3"]
                             ]}})
