(ns sabe.apps.webserver-output
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

(defrecord WebServerOutputApp
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
                        (log/tracef "Posting message to bus with client-id %s"
                                    partition-key)
                        (b/publish! bus partition-key data)))
                    records)))
          worker (worker stream process-fn this)]
      (start! worker)
      (assoc this
             :worker worker
             :bus bus)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil)))
