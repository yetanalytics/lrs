(ns com.yetanalytics.lrs.protocol.auth
  (:require [clojure.spec.alpha :as s]))

(defprotocol LRSAuth
  "Protocol for an authenticatable LRS"
  (authenticate-credentials [this api-key api-key-secret]
    "Authenticate for access to the LRS with key/secret credentials")
  (authenticate-token [this token]
    "Authenticate to the LRS with a token."))

(s/def ::lrs-auth-instance
  #(satisfies? LRSAuth %))
