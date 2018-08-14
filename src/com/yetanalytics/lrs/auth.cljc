(ns com.yetanalytics.lrs.auth
  (:require [clojure.spec.alpha :as s :include-macros true]
            [xapi-schema.spec :as xs]))

;; From https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#42-oauth-10-authorization-scope
(s/def ::scope
  #{:scope/all
    :scope.all/read
    :scope/profile
    :scope/define
    :scope/state
    :scope.statements/read
    :scope.statements.read/mine
    :scope.statements/write})

(def scope-parents
  {:scope/all :scope/all
   :scope.all/read :scope/all
   :scope/profile :scope/all
   :scope/define :scope/all
   :scope/state :scope/all
   :scope.statements/read :scope/all
   :scope.statements.read/mine :scope.statements/read
   :scope.statements/write :scope/all})

(s/def ::scopes
  (s/coll-of ::scope :min-count 1 :kind set? :into #{}))

(defn scope-ancestors [scope]
  (concat
   (drop-while #(= scope %)
               (take-while #(not= :scope/all %)
                           (iterate scope-parents scope)))
   (list :scope/all)))

(s/fdef scope-ancestors
  :args (s/cat :scope ::scope)
  :ret (s/cat :leaf-scopes
              (s/* #{:scope.all/read
                     :scope/profile
                     :scope/define
                     :scope/state
                     :scope.statements/read
                     :scope.statements.read/mine
                     :scope.statements/write})
              :root-scope #{:scope/all}))

(defn resolve-scopes
  [scopes]
  (reduce
   (fn [ss scope]
     (if (= scope :scope/all)
       (conj ss :scope/all)
       (let [ancestors (scope-ancestors scope)]
         (if (some #(contains? scopes %)
                   ancestors)
           ss
           (conj ss scope)))))
   #{}
   scopes))

(s/fdef resolve-scopes
  :args (s/cat :scopes ::scopes)
  :ret ::scopes
  :fn (fn [{:keys [args ret]}]
        (every?
         (fn [scope]
           (let [parent (get scope-parents scope)]
             (or (= scope parent)
                 (not (contains? (:scopes ret) parent)))))
         (:scopes ret))))

(s/def ::prefix
  string?)

(s/def :basic-auth/username (s/and string? not-empty))
(s/def :basic-auth/password (s/and string? not-empty))

(s/def ::basic
  (s/keys :req-un [:basic-auth/username
                   :basic-auth/password]))

(s/def ::token
  (s/and string? not-empty))

(s/def ::no-op
  #{true})

(s/def ::auth
  (s/keys :req-un [(or ::basic
                       ::token
                       ::no-op)]))

(s/def ::identity
  (s/keys :req-un [::scopes ;; what can this person do
                   ::prefix ;; prefix/partition, can be empty string
                   ::auth]
          :opt-un [::xs/agent ;; xapi representation of a person
                   ]))

;; The authenticate method will return identity or nil (401)
;; The other methods need a way to 403
