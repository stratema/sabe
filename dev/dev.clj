(ns dev
  (:require [aleph.http :as http]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer [refresh]]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as component]
            [manifold.stream :as s]
            [reloaded.repl :refer [start stop go system init reset]]
            [sabe.config :as config]
            [sabe.kinesis.client :as kinesis]
            [sabe.logging :as logging]
            [sabe.system :as system]
            [sabe.util :as util])
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

(defn ping-msg [client-id]
  {:message/id (java.util.UUID/randomUUID)
   :message/type :system/echo-request
   :message/data {:sent-time (System/currentTimeMillis)}
   :client/id client-id})

(comment
  (let [ws-base-url "ws://localhost:8080/message/"
        client-id (java.util.UUID/randomUUID)
        conn @(http/websocket-client (str ws-base-url client-id))
        msg (ping-msg client-id)]
    @(s/put! conn (util/clj->msgpack msg))
    (-> @(s/take! conn)
        (util/msgpack->clj)
        (println))
    (s/close! conn)))
