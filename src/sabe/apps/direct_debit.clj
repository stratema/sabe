(ns sabe.apps.direct-debit
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [manifold.bus :as b]
            [manifold.stream :as s]
            [sabe.kinesis.client :as kinesis]
            [sabe.kinesis.worker :refer [worker start! stop!]]
            [sabe.util :as util])
  (:import [com.amazonaws.services.kinesis.model
            Record]
           [com.amazonaws.services.kinesis.clientlibrary.types
            ProcessRecordsInput]))

;; Country/Region specific DDI fulfillment functions
;; UK

;; In reality these would talk to the real backend service/api from
;; the payment service provider in that country. For our demo we will
;; implement a temporary local database of mandates
(defn auddis-create-ddi [db message-data]
  (let [mandate-id (java.util.UUID/randomUUID)
        mandate #:mandate{:id mandate-id :details message-data}]
    (log/tracef "Creating new mandate with id %s" mandate-id)
    (swap! db assoc mandate-id mandate)
    mandate))

(defn auddis-cancel-ddi
  [db {:mandate/keys [id] :as message-data}]
  (log/tracef "Removing mandate with id %s" id)
  (swap! db dissoc id)
  nil)

;; Sweden
(defn autogirot-create-mandate [db message-data])
(defn autogirot-cancel-mandate [db message-data])

(def provider-fns
  {:gb {:create-mandate auddis-create-ddi
        :cancel-mandate auddis-cancel-ddi}
   :se {:create-mandate autogirot-create-mandate
        :cancel-mandate autogirot-cancel-mandate}})

(defn create-mandate [db {:message/keys [id data] :as message}]
  (log/tracef "Calling create-mandate with message %s" message)
  (let [country-code (-> data :address :country-code)
        handler (-> provider-fns country-code :create-mandate)]
    (log/tracef "Calling create-mandate for %s provider" country-code)
    (assoc message
           :message/id (java.util.UUID/randomUUID)
           :message/type :direct-debit/mandate-created
           :message/parent-id id
           :message/data (handler db data))))

(defn cancel-mandate [db {:message/keys [id data] :as message}]
  (let [country-code (-> data :country-code)
        handler (-> provider-fns country-code :cancel-mandate)]
    (assoc message
           :message/id (java.util.UUID/randomUUID)
           :message/type :direct-debit/mandate-cancelled
           :message/parent-id id
           :message/data (handler db data))))

(def message-handlers
  {:direct-debit/create-mandate create-mandate
   :direct-debit/cancel-mandate cancel-mandate})

(defrecord DirectDebitApp
    [name input-stream output-stream kinesis-client]
  component/Lifecycle
  (start [this]
    (let [bus (b/event-bus)
          ;; Dummy DB to record created mandates
          mandate-db (atom {})
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
          worker (worker input-stream process-fn this)]
      (doseq [[message-type handler] message-handlers]
        (log/debug "Subscribing handler for" message-type)
        (let [handler-with-db (partial handler mandate-db)]
          (s/consume
          (fn [{client-id :client/id :as message}]
            (log/tracef "Consuming %s with %s" message handler)
            (try
              (->> (handler-with-db message)
                   (util/clj->msgpack)
                   (kinesis/put (:client kinesis-client) output-stream (str client-id)))
              (catch Exception e
                (log/errorf e "Exception processing %s message" message-type))))
          (b/subscribe bus message-type))))
      (start! worker)
      (assoc this
             :bus bus
             :worker worker
             :mandate-db mandate-db)))

  (stop [{:keys [worker] :as this}]
    (when worker
      (stop! worker))
    (assoc this :worker nil :mandate-db nil)))
