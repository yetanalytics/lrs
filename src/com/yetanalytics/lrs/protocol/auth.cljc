(ns com.yetanalytics.lrs.protocol.auth
  (:require [clojure.spec.alpha :as s]))

(defprotocol LRSAuth
  "Protocol for an authenticatable LRS"
  (authenticate-credentials [this api-key api-key-secret]
    "Authenticate for access to the LRS with key/secret credentials.")
  (authenticate-token [this token]
    "Authenticate to the LRS with a token."))

(s/def ::lrs-auth-instance
  #(satisfies? LRSAuth %))

(s/def ::api-key
  string?)

(s/def ::api-key-secret
  string?)

(s/def ::token
  string?)

(def authenticate-credentials-partial-spec
  (s/fspec :args (s/cat :api-key
                        ::api-key
                        :api-key-secret
                        ::api-key-secret)))

(def authenticate-token-partial-spec
  (s/fspec :args (s/cat :token
                        ::token)))
