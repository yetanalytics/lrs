(ns com.yetanalytics.lrs.pedestal.http.multipart-mixed-test
  (:require [clojure.test :as test :refer [deftest is] :include-macros true]
            [com.yetanalytics.lrs.pedestal.http.multipart-mixed :as multipart]
            [clojure.spec.alpha :as s :include-macros true]
            [com.yetanalytics.lrs.pedestal.interceptor.xapi.statements :as ss]
            #?(:cljs [fs]
               :clj [clojure.java.io :as io])))

(def header
  "Content-Type:application/octet-stream\r\nContent-Transfer-Encoding:binary\r\nX-Experience-API-Hash:20a919870593a42d81370fcc23725b40e19bbafadb15498683ffd45adc82928f")

(deftest parse-body-headers-test
  (let [parsed (multipart/parse-body-headers header)]
    (is (= parsed
           {"Content-Type"              "application/octet-stream"
            "Content-Transfer-Encoding" "binary"
            "X-Experience-API-Hash"     "20a919870593a42d81370fcc23725b40e19bbafadb15498683ffd45adc82928f"}))))

(def body
  "\r\n---------------2199765322\r\nContent-Type:application/json\r\n\r\n{\"actor\":{\"objectType\":\"Agent\",\"name\":\"xAPI mbox\",\"mbox\":\"mailto:xapi@adlnet.gov\"},\"verb\":{\"id\":\"http://adlnet.gov/expapi/verbs/attended\",\"display\":{\"en-GB\":\"attended\",\"en-US\":\"attended\"}},\"object\":{\"objectType\":\"Activity\",\"id\":\"http://www.example.com/meetings/occurances/34534\"},\"id\":\"64343f68-6ebe-42d7-8bc1-a1c8c1be635b\",\"attachments\":[{\"usageType\":\"http://adlnet.gov/expapi/attachments/signature\",\"display\":{\"en-US\":\"Signed by the Test Suite\"},\"description\":{\"en-US\":\"Signed by the Test Suite\"},\"contentType\":\"application/octet-stream\",\"length\":796,\"sha2\":\"20a919870593a42d81370fcc23725b40e19bbafadb15498683ffd45adc82928f\"}]}\r\n---------------2199765322\r\nContent-Type:application/octet-stream\r\nContent-Transfer-Encoding:binary\r\nX-Experience-API-Hash:20a919870593a42d81370fcc23725b40e19bbafadb15498683ffd45adc82928f\r\n\r\neyJhbGciOiJSUzI1NiJ9.eyJhY3RvciI6eyJvYmplY3RUeXBlIjoiQWdlbnQiLCJuYW1lIjoieEFQSSBtYm94IiwibWJveCI6Im1haWx0bzp4YXBpQGFkbG5ldC5nb3YifSwidmVyYiI6eyJpZCI6Imh0dHA6Ly9hZGxuZXQuZ292L2V4cGFwaS92ZXJicy9hdHRlbmRlZCIsImRpc3BsYXkiOnsiZW4tR0IiOiJhdHRlbmRlZCIsImVuLVVTIjoiYXR0ZW5kZWQifX0sIm9iamVjdCI6eyJvYmplY3RUeXBlIjoiQWN0aXZpdHkiLCJpZCI6Imh0dHA6Ly93d3cuZXhhbXBsZS5jb20vbWVldGluZ3Mvb2NjdXJhbmNlcy8zNDUzNCJ9LCJpZCI6IjY0MzQzZjY4LTZlYmUtNDJkNy04YmMxLWExYzhjMWJlNjM1YiJ9.QWNSf1fViOwk78lkFd5IZaxd_JafCaCJEvjshLNvyPl3CfhC7CJmk8a6Pe3uX_38aI70xdSSIaMzx5Dwj0b7Pd6ZL3YNs-mx7xS4f3pICS3ELoUPOw53qlUKGmwRGzrKMDsdKj3QzGEh_AJu3zPDvRHo6wHYguqAcGi7HvnvpgC46tRMAHSeOucm29gYNJjQdt0UOLGkUKULn4Tt7n9ubuhVWRoqZvQ69_65HybTHpfzrC9Ef7PIY-8Q8MmXSZyvIxEKdn6pDEY0KPnjYB0oweARNevHW0Xt01-GFJiki0ddcgV34mfAFHsThbVHxGmIqK9o7wVMKlsiCOv0Vgr8uw\r\n---------------2199765322--")

(def boundary "-------------2199765322")

(deftest parse-parts-test
  (is (nil? (s/explain-data
             (s/cat :statement-part ::ss/statement-part
                    :multiparts (s/* ::ss/multipart))
             #?(:clj (with-open [in (io/input-stream (.getBytes body "UTF-8"))]
                       (multipart/parse-parts in boundary))
                :cljs (multipart/parse-parts body boundary))))))
