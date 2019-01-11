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

(ns io.pedestal.interceptor
  "Public API for creating interceptors, and various utility fns for
  common interceptor creation patterns.")

(defrecord Interceptor [name enter leave error])

(extend-protocol IPrintWithWriter
  Interceptor
  (-pr-writer [i writer _]
    (write-all writer (str "#Interceptor{:name " (.-name i) "}"))))

(defprotocol IntoInterceptor
  (-interceptor [t] "Given a value, produce an Interceptor Record."))

(declare interceptor)
(extend-protocol IntoInterceptor
  cljs.core/PersistentArrayMap
  (-interceptor [t] (map->Interceptor t))
  cljs.core/PersistentHashMap
  (-interceptor [t] (map->Interceptor t))

  ; This is the `handler` case
  function
  (-interceptor [t]
    (let [int-meta (meta t)]
      ;; To some degree, support backwards compatibility
      (if (or (:interceptor int-meta)
              (:interceptorfn int-meta))
        (interceptor (t))
        (interceptor {:enter (fn [context]
                               (assoc context :response (t (:request context))))}))))

  cljs.core/Var
  (-interceptor [t] (interceptor (deref t)))

  Interceptor
  (-interceptor [t] t))

(defn interceptor-name
  [n]
  (if-not (or (nil? n) (keyword? n))
    (throw (ex-info (str "Name must be keyword or nil; Got: " (pr-str n)) {:name n}))
    n))

(defn interceptor?
  [o]
  (= (type o) Interceptor))

(defn valid-interceptor?
  [o]
  (if-let [int-vals (and (interceptor? o)
                           (vals (select-keys o [:enter :leave :error])))]
    (and (some identity int-vals)
         (every? fn? (remove nil? int-vals))
         (or (interceptor-name (:name o)) true) ;; Could return `nil`
         true)
    false))

(defn interceptor
  "Given a value, produces and returns an Interceptor (Record)."
  [t]
  {:pre [(if-not (satisfies? IntoInterceptor t)
           (throw (ex-info "You're trying to use something as an interceptor that isn't supported by the protocol; Perhaps you need to extend it?"
                           {:t t
                            :type (type t)}))
           true)]
   :post [(valid-interceptor? %)]}
  (-interceptor t))
