(ns sabe.apps.logging
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

(defrecord LoggingApp [name stream]
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
          worker (worker stream process-fn this)]
      (start! worker)
      (assoc this :worker worker)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil)))
