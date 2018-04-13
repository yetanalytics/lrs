(ns com.yetanalytics.lrs.xapi.agents
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]))

(def ifi-keys #{"mbox" "mbox_sha1sum" "openid" "account"})

(s/def ::ifi-lookup
  (s/or
   :mbox-lookup (s/tuple #{"mbox"} :agent/mbox)
   :mbox_sha1sum-lookup (s/tuple #{"mbox_sha1sum"} :agent/mbox_sha1sum)
   :openid-lookup (s/tuple #{"openid"} :agent/openid)
   :account-lookup (s/tuple #{"account"} :agent/account)))

(defn find-ifi [actor]
  (some (partial find actor)
        ifi-keys))

(s/fdef find-ifi
        :args (s/cat :actor ::xs/actor)
        :ret (s/nilable ::ifi-lookup))

(defn ifi-match? [a1 a2]
  (or (some (fn [[a1m a2m]]
              (= a1m a2m))
            (for [ifi-key ["mbox" "mbox_sha1sum" "openid" "account"]
                  :let [a1-match (get a1 ifi-key)]
                  :when a1-match
                  :let [a2-match (get a2 ifi-key)]
                  :when a2-match]
              [a1-match a2-match]))
      false))

(s/fdef ifi-match?
        :args (s/cat :a1 ::xs/actor
                     :a2 ::xs/actor)
        :ret boolean?)


(def person-vector-keys (vec (conj ifi-keys "name")))

(defn person-conj [person agent]
  (reduce-kv
   (fn [p k v]
     (update p k (comp
                  (partial into [] (distinct))
                  (fnil conj []))
             v))
   (or person {"objectType" "Person"})
   (select-keys agent person-vector-keys)))

(s/fdef person-conj
        :args (s/cat :person
                     (s/alt :person :xapi.agents.GET.response/person
                            :nil nil?)
                     :agent
                     ::xs/agent)
        :ret :xapi.agents.GET.response/person)

(defn person [& agents]
  (reduce
   person-conj
   {"objectType" "Person"}
   agents))

(s/fdef person
        :args (s/cat :agent (s/* ::xs/agent))
        :ret :xapi.agents.GET.response/person)

(defn actor-seq
  "Given an agent/group, return a seq of all agents/groups expressed"
  [actor]
  (cons actor
        (when (= "Group" (get actor "objectType"))
          (get actor "member"))))

(s/fdef actor-seq
        :args (s/cat :actor ::xs/actor)
        :ret (s/coll-of ::xs/actor))
