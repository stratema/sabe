(ns sabe.apps.system
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [manifold.bus :as b]
            [manifold.stream :as s]
            [sabe.logging :as logging]
            [sabe.kinesis.client :as kinesis]
            [sabe.util :as util]
            [sabe.kinesis.worker :refer [worker start! stop!]])
  (:import [com.amazonaws.services.kinesis.model
            Record]
           [com.amazonaws.services.kinesis.clientlibrary.types
            ProcessRecordsInput]))

(def system-handlers
  {:system/echo-request
   (fn [{:message/keys [id data] :as message}]
     (assoc message
            :message/id (java.util.UUID/randomUUID)
            :message/type :system/echo-response
            :message/parent-id id
            :message/data (assoc data :received-time (System/currentTimeMillis))))})

(defrecord SystemApp
    [name input-stream output-stream kinesis-client]
  component/Lifecycle
  (start [this]
    (let [bus (b/event-bus)
          process-fn
          (fn [^ProcessRecordsInput input]
            (let [records (.getRecords input)]
              (run! (fn [^Record record]
                      (let [partition-key (.getPartitionKey record)
                            message (-> record .getData .array util/msgpack->clj)
                            message-type (:message/type message)]
                        (log/tracef "Received message of type %s" message-type)
                        (b/publish! bus message-type message)))
                    records)))
          worker (worker input-stream process-fn
                         (assoc kinesis-client :name name))]
      (doseq [[message-type handler] system-handlers]
        (log/debug "Subscribing handler for" message-type)
        (s/consume
         (fn [{client-id :client/id :as message}]
           (let [reply (handler message)]
             (log/tracef "Replying with message type %s to %s"
                         (:message/type reply) client-id)
             (kinesis/put (:client kinesis-client) output-stream (str client-id)
                          (util/clj->msgpack reply))))
         (b/subscribe bus message-type)))
      (start! worker)
      (assoc this :worker worker)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil)))
