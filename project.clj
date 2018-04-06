(defproject com.yetanalytics/lrs "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.yetanalytics/xapi-schema "1.0.0-alpha10-SNAPSHOT"]]
  :profiles {:dev
             {:dependencies [[org.clojure/test.check "0.10.0-alpha2"]]}})
