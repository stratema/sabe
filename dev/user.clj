(ns user
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [stm.bbe.kinesis :as kinesis]
            [stm.bbe.logging :as logging])
  (:import [com.amazonaws.services.kinesis.model
            Record]
           [com.amazonaws.services.kinesis.clientlibrary.types
            InitializationInput
            ProcessRecordsInput
            ShutdownInput]))

(set! *warn-on-reflection* true)

(defn dev []
  (require 'dev)
  (in-ns 'dev))
