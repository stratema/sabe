(ns sabe.kinesis.worker
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [com.stuartsierra.component :as component]
            [sabe.logging :as logging]
            [sabe.kinesis.util :as util])
  (:import [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.services.kinesis.clientlibrary.interfaces.v2
            IRecordProcessor
            IRecordProcessorFactory]
           [com.amazonaws.services.kinesis.clientlibrary.lib.worker
            KinesisClientLibConfiguration
            Worker
            Worker$Builder]))

(defn- config
  [stream
   {:keys [credentials-profile worker-id name region version failover-time]
    :or   {credentials-profile "sabe"
           region              "eu-west-1"
           version             "0.1.0"
           failover-time       30000}
    :as   opts}]
  (doto (KinesisClientLibConfiguration.
         name stream
         (ProfileCredentialsProvider. (or credentials-profile name))
         (or worker-id (util/unique-client-id)))
    (.withRegionName region)
    (.withFailoverTimeMillis failover-time)
    (.withCommonClientConfig (util/client-configuration name version))))

(defn worker
  [stream process-fn {:keys [name init-fn shutdown-fn] :as opts}]
  (let [factory
        (reify IRecordProcessorFactory
          (createProcessor [_]
            (reify IRecordProcessor
              (initialize [_ input]
                (logging/mdc-put {:app      name
                                  :stream   stream
                                  :shard-id (.getShardId input)})
                (when (fn? init-fn) (init-fn input))
                (log/info "Started"))
              (processRecords [_ input]
                (process-fn input))
              (shutdown [_ input]
                (when (fn? shutdown-fn) (shutdown-fn input))
                (log/info "Stopped")))))]
    (-> (Worker$Builder.)
        (.recordProcessorFactory factory)
        (.config (config stream opts))
        (.build))))

(defn start! [^Worker worker]
  (doto (Thread. worker)
    (.start))
  worker)

(defn stop! [^Worker worker]
  (when (instance? Worker worker)
    @(.startGracefulShutdown worker))
  worker)
