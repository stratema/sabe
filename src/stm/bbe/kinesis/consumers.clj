(ns stm.bbe.kinesis.consumers
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [manifold.bus :as b]
            [stm.bbe.logging :as logging]
            [stm.bbe.kinesis.client :as client]
            [stm.bbe.kinesis.worker :refer [worker start! stop!]])
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

(defrecord EchoConsumer [name input-stream output-stream kinesis-client]
  component/Lifecycle
  (start [this]
    (let [process-fn
          (fn [^ProcessRecordsInput input]
            (let [records (.getRecords input)]
              (log/infof "Received %d records" (count records))
              (run! (fn [^Record record]
                      (let [partition-key (.getPartitionKey record)
                            bytes (.array (.getData record))]
                        (log/infof "Posting message to output-stream %s %s"
                                   partition-key
                                   (String. bytes "UTF-8"))
                        (client/put (:client kinesis-client) output-stream
                                    partition-key bytes)))
                    records)))
          worker (worker input-stream process-fn
                         (assoc kinesis-client :name name))]
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
              (log/infof "Received %d records" (count records))
              (run! (fn [^Record record]
                      (let [partition-key (.getPartitionKey record)
                            data (String. (.array (.getData record)))]
                        (log/infof "Posting message to client %s %s"
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
