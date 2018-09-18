(ns sabe.kinesis.consumers
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
            InitializationInput
            ProcessRecordsInput
            ShutdownInput]))

(defrecord LoggingConsumer [name stream kinesis-client]
  component/Lifecycle
  (start [this]
    (let [process-fn (fn [^ProcessRecordsInput input]
                       (let [records (.getRecords input)]
                         (log/infof "Received %d records" (count records))
                         (run! (fn [^Record record]
                                 (log/infof "Received %s %s"
                                            (.getPartitionKey record)
                                            (String. (.array (.getData record)))))
                               records)))
          worker (worker stream process-fn (assoc kinesis-client :name name))]
      (start! worker)
      (assoc this :worker worker)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil)))

(def system-handlers
  {:system/echo-request
   (fn [{:message/keys [id data] :as message}]
     (assoc message
            :message/id (java.util.UUID/randomUUID)
            :message/type :system/echo-response
            :message/parent-id id
            :message/data (assoc data :received-time (System/currentTimeMillis))))})

(defrecord SystemConsumer
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
                        (b/publish! bus message-type message)))
                    records)))
          worker (worker input-stream process-fn
                         (assoc kinesis-client :name name))]
      (doseq [[message-type handler] system-handlers]
        (log/debug "Subscribing handler for" message-type)
        (s/consume
         (fn [{client-id :client/id :as message}]
           (kinesis/put (:client kinesis-client) output-stream (str client-id)
                        (-> message handler util/clj->msgpack)))
         (b/subscribe bus message-type)))
      (start! worker)
      (assoc this :worker worker)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil)))

(defrecord WebServerOutputConsumer
    [bus kinesis-client name stream]
  component/Lifecycle
  (start [this]
    (let [bus (b/event-bus)
          process-fn
          (fn [^ProcessRecordsInput input]
            (let [records (.getRecords input)]
              (run! (fn [^Record record]
                      (let [partition-key (.getPartitionKey record)
                            data (.array (.getData record))]
                        (log/tracef "Posting message to client %s %s"
                                   partition-key data)
                        (b/publish! bus partition-key data)))
                    records)))
          worker (worker stream process-fn
                         (assoc kinesis-client :name name))]
      (start! worker)
      (assoc this
             :worker worker
             :bus bus)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil)))
