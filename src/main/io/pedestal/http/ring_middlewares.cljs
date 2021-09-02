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

(ns io.pedestal.http.ring-middlewares
  "This namespace creates interceptors for ring-core middlewares."
  (:require [io.pedestal.http.params :as pedestal-params]
            [io.pedestal.interceptor :refer [interceptor
                                             interceptor-name]]
            [macchiato.util.mime-type :as mime]))

(defn response-fn-adapter
  "Adapts a ring middleware fn taking a response and request to an interceptor context."
  ([response-fn]
   (fn [{:keys [request response] :as context}]
     (if response
       (assoc context :response (response-fn response request))
       context)))
  ([response-fn opts]
   (if (seq opts)
     (fn [{:keys [request response] :as context}]
       (if response
         (assoc context :response (response-fn response request opts))
         context))
     (response-fn-adapter response-fn))))

(defn- after
  "Return an interceptor which calls `f` on context during the leave
  stage."
  ([f] (interceptor {:leave f}))
  ([f & args]
   (let [[n f args] (if (fn? f)
                      [nil f args]
                      [f (first args) (rest args)])]
     (interceptor {:name (interceptor-name n)
                   :leave #(apply f % args)}))))

(defn- leave-interceptor
  "Defines an leave only interceptor given a ring fn."
  [name response-fn & [args]]
  (after name (response-fn-adapter response-fn args)))

(defn- content-type-response
  "Tries adding a content-type header to response by request URI (unless one
  already exists)."
  [resp req & [opts]]
  (if-let [mime-type (or (get-in resp [:headers "Content-Type"])
                         (mime/ext-mime-type (:uri req) (:mime-types opts)))]
    (assoc-in resp [:headers "Content-Type"] mime-type)
    resp))

(defn content-type
  "Interceptor for content-type ring middleware."
  [& [opts]]
  (leave-interceptor ::content-type-interceptor content-type-response opts))

(defn- middleware
  "Returns an interceptor which calls `f1` on the :request value of
  context during the enter stage, and `f2` on the :response value of
  context during the leave stage."
  ([f1 f2]
   (interceptor {:enter (when f1 #(update-in % [:request] f1))
                 :leave (when f2 #(update-in % [:response] f2))}))
  ([n f1 f2]
   (interceptor {:name (interceptor-name n)
                 :enter (when f1 #(update-in % [:request] f1))
                 :leave (when f2 #(update-in % [:response] f2))})))
