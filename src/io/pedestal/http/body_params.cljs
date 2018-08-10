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

(ns io.pedestal.http.body-params
  (:require ;; [clojure.edn :as edn]
            ;; [cheshire.core :as json]
            ;; [cheshire.parse :as parse]
            [clojure.string :as str]
            [io.pedestal.http.params :as pedestal-params]
            ;; [io.pedestal.interceptor.helpers :as interceptor]
            ;; [io.pedestal.log :as log]
            [cognitect.transit :as transit]
            ;; [ring.middleware.params :as params]
            [macchiato.middleware.params :as params]
            [goog.string :as gstring]
            [cljs.reader :refer [read-string]]
            [io.pedestal.interceptor :as i]
            [clojure.core.async :as a :include-macros true]
            [concat-stream])
  #_(:import [java.util.regex Pattern]))

(defn body-string-chan
  [body]
  (let [out (a/promise-chan)]
    (.pipe body
           (concat-stream. (fn [body-buffer]
                             (a/go (a/>! out
                                         (.toString body-buffer))))))))

(defn- parser-for
  "Find a parser for the given content-type, never returns nil"
  [parser-map content-type]
  (or (when content-type
        (parser-map (some #(when (re-find % content-type) %) (keys parser-map))))
      identity))

(defn- parse-content-type
  "Runs the request through the appropriate parser"
  [parser-map request]
  ((parser-for parser-map (:content-type request)) request))

(defn- set-content-type
  "Changes the content type of a request"
  [request content-type]
  (-> request
      (assoc :content-type content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(defn- convert-middleware
  "Turn a ring middleware into a parser. If a content type is given, return a parser
   that will ensure that the handler sees that content type in the request"
  ([wrap-fn] (fn [request] (wrap-fn identity)))
  ([wrap-fn expected-content-type]
      (let [parser (wrap-fn identity)]
        (fn [request]
          (let [retyped-request (set-content-type request expected-content-type)
                parsed-request (parser retyped-request)]
            (set-content-type parsed-request (:content-type request)))))))

(defn add-parser
  [parser-map content-type parser-fn]
  (let [content-pattern (if (instance? js/RegExp content-type)
                          content-type
                          (re-pattern (str "^" (gstring/regExpEscape content-type) "$")))]
    (assoc parser-map content-pattern parser-fn)))

(defn add-ring-middleware
  [parser-map content-type middleware]
  (add-parser parser-map content-type (convert-middleware middleware)))

(defn custom-edn-parser
  "Return an edn-parser fn that, given a request, will read the body of that
  request with `edn/read`. options are key-val pairs that will be passed as a
  hash-map to `edn/read`."
  [& options]
  (let [edn-options (merge {:eof nil}
                           (apply hash-map options))]
    (fn [request]
      (if-let [prc (::parsed-request-chan request)]
        ;; Async body and interceptor
        (a/go (let [pec (::parse-error-chan request)
                    body-str (a/<! (body-string-chan (:body request)))]
                (try (let [parsed (read-string body-str edn-options)]
                       (a/>! prc (assoc request :edn-params parsed)))
                     (catch js/Error e
                       (a/>! pec (ex-info "EDN Parse Error"
                                          {:type ::edn-parse-error}
                                          e))))))
        ;; Sync body
        (assoc request :edn-params (read-string (:body request) edn-options))))))

(def edn-parser
  "Take a request and parse its body as edn."
  (custom-edn-parser))

(defn- json-read
  "Parse json stream, supports parser-options with key-val pairs:

    :bigdec Boolean value which defines if numbers are parsed as BigDecimal
            or as Number with simplest/smallest possible numeric value.
            Defaults to false.
    :key-fn Key coercion, where true coerces keys to keywords, false leaves
            them as strings, or a function to provide custom coercion.
    :array-coerce-fn Define a collection to be used for array values by name."
  [s & options]
  (js->clj (.parse js/JSON s)
           :keywordize-keys (if (true? (:key-fn options))
                              true
                              false))
  #_(let [{:keys [bigdec key-fn array-coerce-fn]
         :or {bigdec false
              key-fn keyword
              array-coerce-fn nil}} options]
    (binding [parse/*use-bigdecimals?* bigdec]
      (json/parse-stream (java.io.PushbackReader. reader) key-fn array-coerce-fn))))

(defn custom-json-parser
  "Return a json-parser fn that, given a request, will read the body of that
  request with `json/read`. options are key-val pairs that will be passed along
  to `json/read`."
  [& options]
  (fn [request]
    (let [encoding (or (:character-encoding request) "UTF-8")]
      (if-let [prc (::parsed-request-chan request)]
        ;; Async body and interceptor
        (a/go (let [pec (::parse-error-chan request)
                    body-str (a/<! (body-string-chan (:body request)))]
                (try (let [parsed (apply json-read body-str options)]
                       (a/>! prc (assoc request :json-params parsed)))
                     (catch js/Error e
                       (a/>! pec (ex-info "JSON Parse Error"
                                          {:type ::json-parse-error}
                                          e))))))
        ;; Sync body
        (assoc request :json-params (apply json-read (:body request) options)))
      #_(assoc request
             :json-params
             (apply json-read
                    (java.io.InputStreamReader.
                      ^java.io.InputStream (:body request)
                      ^String encoding)
                    options)))))

(def json-parser
  "Take a request and parse its body as json."
  (custom-json-parser))

(defn custom-transit-parser
  "Return a transit-parser fn that, given a request, will read the
  body of that request with `transit/read`. options is a sequence to
  pass to transit/reader along with the body of the request."
  [& options]
  (fn [{:keys [body] :as request}]
    (if-let [prc (::parsed-request-chan request)]
      ;; Async body and interceptor
      (a/go (let [pec (::parse-error-chan request)
                  body-str (a/<! (body-string-chan (:body request)))]
              (try (let [parsed (transit/read
                                 (apply transit/reader options)
                                 body-str)]
                     (a/>! prc (assoc request :transit-params parsed)))
                   (catch js/Error e
                     (a/>! pec (ex-info "Transit Parse Error"
                                        {:type ::transit-parse-error}
                                        e))))))
      ;; Sync body
      (assoc request :transit-params (transit/read (apply transit/reader options)
                                                (:body request))))

    #_(if (zero? (.available ^java.io.InputStream body))
      request
      (assoc request
             :transit-params
             (transit/read (apply transit/reader body options))))))

(def transit-parser
  "Take a request and parse its body as transit."
  (custom-transit-parser :json))

(defn form-parser
  "Take a request and parse its body as a form."
  [request]
  (if-let [prc (::parsed-request-chan request)]
    ;; Async body and interceptor
    (a/go (let [pec (::parse-error-chan request)
                body-str (a/<! (body-string-chan (:body request)))]
            (try (let [parsed-req (assoc (params/assoc-form-params
                                          (assoc request :body body-str))
                                         ;; keep the body intact
                                         :body (:body request))]
                   (a/>! prc parsed-req))
                 (catch js/Error e
                   (a/>! pec (ex-info "Form Parse Error"
                                      {:type ::form-parse-error}
                                      e))))))
    ;; Sync body
    (params/assoc-form-params
     request))

  #_(let [encoding (or (:character-encoding request) "UTF-8")
        request  (params/assoc-form-params request encoding)]
    (update request :form-params pedestal-params/keywordize-keys)))

(defn default-parser-map
  "Return a map of MIME-type to parsers. Included types are edn, json and
  form-encoding. parser-options are key-val pairs, valid options are:

    :edn-options A hash-map of options to be used when invoking `edn/read`.
    :json-options A hash-map of options to be used when invoking `json/parse-stream`.
    :transit-options A vector of options to be used when invoking `transit/reader` - must apply to both json and msgpack

  Examples:

  (default-parser-map :json-options {:key-fn keyword})
  ;; This parser-map would parse the json body '{\"foo\": \"bar\"}' as
  ;; {:foo \"bar\"}

  (default-parser-map :edn-options {:readers *data-readers*})
  ;; This parser-map will parse edn bodies using any custom edn readers you
  ;; define (in a data_readers.clj file, for example.)

  (default-parser-map :transit-options [{:handlers {\"custom/model\" custom-model-read-handler}}])
  ;; This parser-map will parse the transit body using a handler defined by
  ;; custom-model-read-handler."
  [& parser-options]
  (let [{:keys [edn-options json-options transit-options]} (apply hash-map parser-options)
        edn-options-vec (apply concat edn-options)
        json-options-vec (apply concat json-options)]
    {#"^application/edn" (apply custom-edn-parser edn-options-vec)
     #"^application/json" (apply custom-json-parser json-options-vec)
     #"^application/x-www-form-urlencoded" form-parser
     #"^application/transit\+json" (apply custom-transit-parser :json transit-options)
     #"^application/transit\+msgpack" (apply custom-transit-parser :msgpack transit-options)}))

(defn body-params
  ([] (body-params (default-parser-map)))
  ([parser-map]
   (i/interceptor
    {:name ::body-params
     :enter (fn [{:keys [request] :as ctx}]
              (cond
                (nil? (:body request)) ctx
                (empty? (:body request)) ctx
                (string? (:body request))
                (assoc ctx :request (parse-content-type parser-map request))
                :else (let [ctx-chan (a/promise-chan)
                            parsed-request-chan (a/promise-chan)
                            parse-error-chan (a/promise-chan)]
                        (a/go (let [[v p] (a/alts! [parsed-request-chan
                                                    parse-error-chan])]
                                (a/>! ctx-chan (if (= parsed-request-chan p)
                                                 (assoc ctx :request
                                                        (dissoc (a/<! parsed-request-chan)
                                                                ::parsed-request-chan
                                                                ::parse-error-chan))
                                                 ;; otherwise, it's an error exi,
                                                 ;; and we want to throw it to pedestal
                                                 (assoc ctx :io.pedestal.interceptor.chain/error
                                                        v)))))
                        ;; We run it async...
                        (parse-content-type
                         parser-map (assoc request
                                           ::parsed-request-chan
                                           parsed-request-chan
                                           ::parse-error-chan
                                           parse-error-chan))
                        ctx-chan)))})
     #_(interceptor/on-request ::body-params
                             (fn [request] (parse-content-type parser-map request)))))
