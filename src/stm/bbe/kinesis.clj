(ns stm.bbe.kinesis
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [com.stuartsierra.component :as component]
            [stm.bbe.logging :as logging])
  (:import [java.nio ByteBuffer]
           [com.amazonaws AmazonClientException ClientConfiguration]
           [com.amazonaws.auth AWSCredentialsProvider]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.regions
            Region
            RegionUtils]
           [com.amazonaws.services.kinesis
            AmazonKinesis
            AmazonKinesisClient
            AmazonKinesisClientBuilder]
           [com.amazonaws.services.kinesis.model
            DescribeStreamResult
            PutRecordRequest
            ResourceNotFoundException]
           [com.amazonaws.services.kinesis.clientlibrary.interfaces
            IRecordProcessorCheckpointer]
           [com.amazonaws.services.kinesis.clientlibrary.interfaces.v2
            IRecordProcessor
            IRecordProcessorFactory]
           [com.amazonaws.services.kinesis.clientlibrary.lib.worker
            KinesisClientLibConfiguration
            Worker
            Worker$Builder]
           [com.amazonaws.services.kinesis.clientlibrary.types
            InitializationInput
            ProcessRecordsInput
            ShutdownInput]))

(set! *warn-on-reflection* true)

;; Stream arn:aws:kinesis:eu-west-1:461824180949:stream/StockTradeStream
;; DynamoDB arn:aws:dynamodb:eu-west-1:461824180949:table/StockTradesProcessor


(defn unique-client-id
  ([] (unique-client-id (str (java.net.InetAddress/getLocalHost))))
  ([ip-address] (str ip-address ":" (java.util.UUID/randomUUID))))

(defn client-configuration
  [name version]
  (doto (ClientConfiguration.)
    (.setUserAgentPrefix
     (string/join [ClientConfiguration/DEFAULT_USER_AGENT " " name "/" version]))
    (.setUserAgentSuffix nil)))

(defn create-client
  [{:keys [credentials-profile name region version] :as opts}]
  (try
    (let [config (client-configuration name version)
          provider (ProfileCredentialsProvider. credentials-profile)
          builder (doto (AmazonKinesisClientBuilder/standard)
                    (.setRegion region)
                    (.setClientConfiguration config)
                    (.setCredentials provider))]
      (.build builder))))

(defn active?
  [^AmazonKinesis client ^String name]
  (try
    (let [^DescribeStreamResult r (.describeStream client name)]
      (= "ACTIVE"
         (.. r getStreamDescription getStreamStatus)))
    (catch ResourceNotFoundException e
      (log/errorf e "Stream %s not found" name))
    (catch Exception e
      (log/error e "Error when describing stream" name))))

(defn put
  [^AmazonKinesis client name partition-key data]
  (try
    (.putRecord client
                (doto (PutRecordRequest.)
                  (.setStreamName name)
                  (.setPartitionKey partition-key)
                  (.setData (ByteBuffer/wrap data))))
    (catch AmazonClientException e
      (log/error e "Error sending record to Kinesis"))))

(defn- kcl-config
  [stream
   {:keys [credentials-profile worker-id name region version failover-time]
    :as opts}]
  (doto (KinesisClientLibConfiguration.
         name stream
         (ProfileCredentialsProvider. (or credentials-profile name))
         (or worker-id (unique-client-id)))
    (.withRegionName region)
    (.withFailoverTimeMillis failover-time)
    (.withCommonClientConfig (client-configuration name version))))

(defn worker
  [stream process-fn {:keys [init-fn shutdown-fn] :as opts}]
  (let [factory
        (reify IRecordProcessorFactory
          (createProcessor [_]
            (reify IRecordProcessor
              (initialize [_ input]
                (logging/mdc-put {:shard-id (.getShardId input)})
                (when (fn? init-fn) (init-fn input)))
              (processRecords [_ input]
                (process-fn input))
              (shutdown [_ input]
                (when (fn? shutdown-fn) (shutdown-fn input))))))]
    (-> (Worker$Builder.)
        (.recordProcessorFactory factory)
        (.config (kcl-config stream opts))
        (.build))))

(defn start! [^Worker worker]
  (doto (Thread. worker)
    (.start))
  worker)

(defn stop! [^Worker worker]
  (when (instance? Worker worker)
    @(.startGracefulShutdown worker))
  worker)

(defrecord KinesisClient
    [client name version region credentials-profile]
  component/Lifecycle
  (start [this]
    (assoc this :client
           (create-client {:name name
                           :region region
                           :version version
                           :credentials-profile credentials-profile})))

  (stop [this]
    (assoc this :client nil)))
