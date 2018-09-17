(ns dev
  (:require [aleph.http :as http]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [manifold.stream :as s]
            [reloaded.repl :refer [start stop go system init reset]]
            [stm.bbe.config :as config]
            [stm.bbe.kinesis :as kinesis]
            [stm.bbe.logging :as logging]
            [stm.bbe.system :as system])
  (:import [ch.qos.logback.classic Logger]
           [org.slf4j LoggerFactory]
           [com.amazonaws.services.kinesis.model
            Record]
           [com.amazonaws.services.kinesis.clientlibrary.types
            InitializationInput
            ProcessRecordsInput
            ShutdownInput]))


(defn dev-system
  []
  (system/prod-system))

(defn load-dev-config []
  (let [options-override (some-> (io/resource "options-override.edn")
                                 io/file
                                 slurp
                                 edn/read-string)
        config-override (some-> (io/resource "config-override.edn")
                                io/file
                                .getAbsolutePath)]
    (when options-override
      (println "Using options-override.edn")
      (pprint/pprint options-override))
    (when config-override
      (println "Using config-override.edn")
      (pprint/pprint (edn/read-string (slurp (io/file config-override)))))
    (-> {:config-key (System/getenv "CONFIG_KEY")
         :config-file config-override
         :profile :dev
         :region :nam}
        (merge options-override)
        (config/config))))

(defn new-dev-system []
  (system/new-system (dev-system) (load-dev-config)))

(defn run-all-tests [& [re]]
  (stop)
  (refresh)
  (test/run-all-tests (or re #"stm.*test$")))

(reloaded.repl/set-init! new-dev-system)

(defn set-logging-level!
  ([level] (set-logging-level! "root" level))
  ([ns level]
   (log/info "Setting log level" ns level)
   (let [^Logger logger (->> ns name (.getLogger (LoggerFactory/getILoggerFactory)))]
     (->> level
          name
          .toUpperCase
          (format "ch.qos.logback.classic.Level/%s")
          read-string
          eval
          (.setLevel logger)))))

(comment
  (set-logging-level! 'stm :debug)
  (set-logging-level! 'dev :debug))

(defn members
  [obj]
  (->> (:members (clojure.reflect/reflect obj))
       (sort-by :name)
       (pprint/print-table)))

(def app-opts {:name "stm-bbe"
               :version "0.1.0"
               :region "eu-west-1"
               :failover-time 30000
               :credentials-profile "stm-bbe"})
(def stream "msg-input")

(defn logging-worker
  "A worker that just logs everything"
  [stream opts]
  (kinesis/worker
   stream
   (fn [^ProcessRecordsInput input]
     (let [records (.getRecords input)]
       (log/debugf "Received %d records" (count records))
       (run! (fn [^Record record]
               (log/debugf "Received %s %s"
                           (.getPartitionKey record)
                           (String. (.array (.getData record)))))
             records)))
   (assoc opts
          :init-fn (fn [^InitializationInput input]
                     (log/debug "I've started!"))
          :shutdown-fn (fn [^ShutdownInput input]
                         (log/debug "I've stopped!")))))

#_(let [;; lw (logging-worker stream app-opts)
      conn @(http/websocket-client "ws://localhost:8080/msg/123")]
  ;; (kinesis/start! lw)
  (s/put-all! conn (map #(str "test data " %) (range 100))))
