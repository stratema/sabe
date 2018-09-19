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

;; Dummy DB to record created mandates
(def mandate-db (atom {}))

;; Country/Region specific DDI fulfillment functions
;; UK

;; In reality these would talk to the real backend service/api from
;; the payment service provider in that country. For our demo we will
;; implement a temporary local database of mandates
(defn auddis-create-ddi [message-data]
  (let [mandate-id (java.util.UUID/randomUUID)
        mandate #:mandate{:id mandate-id :details message-data}]
    (swap! mandate-db assoc mandate-id mandate)
    mandate))

(defn auddis-cancel-ddi
  [{:mandate/keys [id] :as message-data}]
  (swap! mandate-db dissoc id)
  nil)

;; Sweden
(defn autogirot-create-mandate [])
(defn autogirot-cancel-mandate [])

(def provider-fns
  {:gb {:create-mandate auddis-create-ddi
        :cancel-mandate auddis-cancel-ddi}
   :se {:create-mandate autogirot-create-mandate
        :cancel-mandate autogirot-cancel-mandate}})

(defn create-mandate [{:message/keys [id data] :as message}]
  (let [country-code (-> data :address :country-code)
        handler (-> provider-fns country-code :create-mandate)]
    (assoc message
           :message/id (java.util.UUID/randomUUID)
           :message/type :direct-debit/mandate-created
           :message/parent-id id
           :message/data (handler data))))

(defn cancel-mandate [{:message/keys [id data] :as message}]
  (let [country-code (-> data :country-code)
        handler (-> provider-fns country-code :create-mandate)]
    (assoc message
           :message/id (java.util.UUID/randomUUID)
           :message/type :direct-debit/mandate-cancelled
           :message/parent-id id
           :message/data (handler data))))

(def message-handlers
  {:direct-debit/create-mandate create-mandate
   :direct-debit/cancel-mandate cancel-mandate})

(defrecord DirectDebitApp
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
          worker (worker input-stream process-fn this)]
      (doseq [[message-type handler] message-handlers]
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
