(ns sabe.kinesis.client
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [com.stuartsierra.component :as component]
            [sabe.logging :as logging]
            [sabe.kinesis.util :as util])
  (:import [java.nio ByteBuffer]
           [com.amazonaws AmazonClientException]
           [com.amazonaws.auth AWSCredentialsProvider]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.services.kinesis
            AmazonKinesis
            AmazonKinesisClient
            AmazonKinesisClientBuilder]
           [com.amazonaws.services.kinesis.model
            DescribeStreamResult
            PutRecordRequest
            ResourceNotFoundException]))

(defn create
  [{:keys [credentials-profile name region version] :as opts}]
  (try
    (let [config (util/client-configuration name version)
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


(defrecord KinesisClient
    [client name version region credentials-profile]
  component/Lifecycle
  (start [this]
    (assoc this :client
           (create {:name                name
                    :region              region
                    :version             version
                    :credentials-profile credentials-profile})))

  (stop [this]
    (assoc this :client nil)))
