(ns com.yetanalytics.lrs.pedestal.openapi
  (:require
   [com.yetanalytics.gen-openapi.generate :as g]
   [com.yetanalytics.gen-openapi.generate.schema :as gs]
   [com.yetanalytics.gen-openapi.core :as gc]))

(def components
  {:securitySchemes
   {:bearerAuth {:type :http
                 :scheme :bearer
                 :bearerFormat :JWT}}
   :responses
   {:error-400 (g/response "Bad Request" :r#Error)
    :error-401 (g/response "Unauthorized" :r#Error)}
   :schemas
   {:Account (gs/o {:homePage :r#IRL
                    :name :t#string})
    :Activity {:type :object
               :required [:id]
               :properties {:objectType {:type :string :pattern "String"}
                            :id :r#IRI
                            :definition {:type :object
                                         :properties {:name {}
                                                      :description {}
                                                      :type {}
                                                      :moreinfo {}
                                                      :extensions {}}}}}
    :Agent                              ;maybe important
    {:allOf [{:type :object
              :properties  {:name :t#string
                            :objectType :t#string}}
             :r#IFI]}
    :Group {:oneOf [{:properties {:objectType {:type :string :pattern "Group"}
                                  :name :t#string
                                  :member (gs/a :r#Agent)}
                     :required [:objectType :member]}
                    {:allOf [{:properties {:objectType {:type :string :pattern "Group"}
                                           :name {:type :string}
                                           :member (gs/a :r#Agent)}
                              :required [:objectType]}
                             :r#IFI]}]}
    :Actor {:oneOf [:r#Group
                    :r#Agent]}
    
    :Error (gs/o {:error :t#string})

    :IFI {:oneOf [(gs/o {:mbox :r#MailToIRI})
                  (gs/o {:mbox_sha1sum :t#string})
                  (gs/o {:openid :r#URI})
                  (gs/o {:account :r#Account})]}
    
    :IRI {:type :string :format :iri}
    :IRL :t#string
    :MailToIRI {:type :string :format :email}
    :KeyPair (gs/o {:api-key :t#string
                    :secret-key :t#string})

    :Person {:type :object
             :properties {:objectType {:type :string :pattern "Person"}
                          :name (gs/a :t#string)
                          :mbox (gs/a :r#MailToIRI)
                          :mbox_sha1sum (gs/a :t#string)
                          :openid* (gs/a :r#URI)
                          :account* (gs/a :r#Account)}
             :required [:objectType]}
    :Scopes (gs/o {:scopes (gs/a :t#string)})
    :ScopedKeyPair {:allOf [:r#KeyPair
                            :r#Scopes]}

    :statementId {:type :string}
    :Statement {:type :object :description "https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Data.md#20-statements"}

    :Timestamp {:type :string :format :date-time}

    :StatementResult {:type :object
                      :required [:statements]
                      :properties {:statements (gs/a :r#Statement)
                                   :more :r#IRL}}
    :URI {:type :string :format :uri}
    :UUID {:type :string :format :uuid}}})

(defn spec [spec-kw]
  (case spec-kw
    :xapi.activities.profile.GET.request/params
    {:params      {:activityId :r#IRI
                   :?#profileId :t#string
                   :?since :r#Timestamp}
     :responses   {200 (g/response "The requested Profile document" :t#object)}
     :operationId        :get-activity-profile
     :description        "Fetches the specified Profile document in the context of the specified Activity.  The semantics of the request are driven by the \"profileId\" parameter. If it is included, the GET method will act upon a single defined document identified by \"profileId\". Otherwise, GET will return the available ids."}


    :xapi.activities.profile.PUT.request/params
    {:params     {:activityId :r#IRI :profileId :t#string}
     :requestBody (g/request :t#object)
     :responses  {204 (g/response "No content")}
     :operationId       :put-activity-profile
     :description "Stores or changes the specified Profile document in the context of the specified Activity."}
    
    :xapi.activities.profile.POST.request/params
    {:params     {:activityId :r#IRI :profileId :t#string}
     :requestBody(g/request :t#object)
     :responses  {204 (g/response "No content")}
     :operationId       :post-activity-profile
     :description        "Stores or changes the specified Profile document in the context of the specified Activity."}
    
    :xapi.activities.profile.DELETE.request/params
    {:params     {:activityId :r#IRI
                  :profileId :t#string}
     :responses  {204 (g/response "No content")}
     :operationId       :delete-activity-profile
     :description       "Deletes the specified Profile document in the context of the specified Activity."}
    
    :xapi.activities.state.GET.request/params
    {:params   {:activityId :r#IRI
                :agent :r#Agent
                :?#registration :r#UUID
                :?#stateId :t#string
                :?#since :r#Timestamp}
     :responses {200 (g/response "The requested state document, or an array of stateId(s)"
                                 {:oneOf [:t#object
                                          (gs/a :t#string)]})}
     :operationId :get-state
     :description   "Fetches the document specified by the given  stateId that exists in the context of the specified Activity, Agent, and registration (if specified), or an array of stateIds."}

    :xapi.activities.state.PUT.request/params
    {:params {:activityId :r#IRI
              :agent :r#Agent
              :?#registration :r#UUID
              :stateId :t#string}
     :requestBody (g/request :t#object)
     :responses {204 (g/response "No content" )}
     :operationId :put-state
     :description "Stores or changes the document specified by the given stateId that exists in the context of the specified Activity, Agent, and registration (if specified)."}
    :xapi.activities.state.POST.request/params
    {:params  {:activityId :r#IRI
               :agent :r#Agent
               :?#registration :r#UUID
               :stateId :t#string}
     :requestBody  (g/request :t#object)
     :responses {204 (g/response "No content" )}
     :operationId :post-state
     :description  "Stores or changes the document specified by the given  stateId that exists in the context of the specified Activity, Agent, and registration (if specified)."}
    :xapi.activities.state.DELETE.request/params
    {:params    {:activityId :r#IRI
                 :agent :r#Agent
                 :?#registration :r#UUID
                 :?#stateId :t#string}
     :responses  {204 (g/response "No content" )}
     :operationId      :delete-state
     :description     "Deletes all documents associated with the specified Activity, Agent, and registration (if specified), or just the document specified by stateId"}
    :xapi.agents.profile.GET.request/params
    {:params  {:agent :r#Agent
               :?#profileId :t#string
               :?#since :r#Timestamp}
     :responses {200 (g/response "If profileId is included in the request, the specified document.  Otherwise, an array of profileId for the specified Agent.")}
     :operationId :get-agents-profile
     :description  "Fetches the specified Profile document in the context of the specified Agent.  The semantics of the request are driven by the \"profileId\" parameter. If it is included, the GET method will act upon a single defined document identified by \"profileId\". Otherwise, GET will return the available ids."}

    :xapi.agents.profile.PUT.request/params
    {:params {:agent :r#Agent
              :profileId :t#string}
     :requestBody (g/request :t#object)
     :responses {204 (g/response "No content")}
     :operationId :put-agents-profile
     :description "Stores or changes the specified Profile document in the context of the specified Agent."}
    :xapi.agents.profile.POST.request/params
    {:params {:agent :r#Agent
              :profileId :t#string}
     :requestBody (g/request :t#object)
     :responses  {204 (g/response "No content")}
     :operationId :post-agents-profile
     :description "Stores or changes the specified Profile document in the context of the specified Agent."}
    :xapi.agents.profile.DELETE.request/params
    {:params {:agent :r#Agent
              :profileId :t#string}
     :responses {204 (g/response "No content")}
     :operationId    :delete-agents-profile
     :description "Deletes the specified Profile document in the context of the specified Agent."}))



(def annotations
  {:health {:operationId :health
            :responses {200 (gc/response "Empty body---a 200 indicates server is alive")}
            :description "Simple heartbeat"}
   :about {:operationId :get-about
           :description "About info"
           :responses
           {200 (gc/response "Object containing body text and optional etag"
                             (gc/o {:body :t#string
                                    :#?etag :t#string}))}}
   :statements-get {:params {:?#statementId :t#string
                       :?#voidedStatementId :t#string
                       :?#agent :r#Actor
                       :?#verb :r#IRI
                       :?#activity :r#IRI
                       :?#registration :r#UUID
                       :?#related_activities :t#boolean
                       :?#related_agents :t#boolean
                       :?#since :r#Timestamp
                       :?#limit :t#integer
                       :?#format :t#string
                       :?#attachments :t#boolean
                       :?#ascending :t#boolean}
              
              :responses {200 (gc/response "Requested Statement or Results"
                                          {:oneOf [:r#Statement
                                                   :r#StatementResult]})}
              :operationId :get-statement
              :description "https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Communication.md#21-statement-resource"}

   :statements-put {:params {:statementId :t#string}
                    :requestBody (gc/request :r#Statement)
                    :responses {204 (gc/response "No content")}
                    :operationId :put-statement
                    :description ""}
   :statements-post {:requestBody (gc/request {:oneOf [(gc/a :r#statementId)
                                                       :r#statementId]})
                     :responses {200 (gc/response "Array of Statement id(s) (UUID) in the same order as the corresponding stored Statements."
                                                  (gc/a :r#statementId))}
                     :operationId :post-statement
                     :description "Stores a Statement, or a set of Statements."}
   :agents-post {:params {:agent :r#Agent}
                 :responses {200 (gc/response "Return a special, Person Object for a specified Agent. The Person Object is very similar to an Agent Object, but instead of each attribute having a single value, each attribute has an array value, and it is legal to include multiple identifying properties."
                                              :r#Person)}
                 :operationId :get-agent
                 :description "Gets a specified agent"}
   :activities-post {:params {:activityId :r#IRI}
                     :responses {200 (gc/response "The requested Activity object"
                                                  :r#Activity)}
                     :operationId :get-activity
                     :description "Gets the Activity with the specified activityId"}})
