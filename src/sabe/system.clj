(ns sabe.system
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sabe.config :as config]
            [sabe.kinesis.client :as kinesis]
            [sabe.apps.direct-debit :as apps-direct-debit]
            [sabe.apps.logging :as apps-logging]
            [sabe.apps.system :as apps-system]
            [sabe.apps.webserver-output :as apps-webserver-output]
            [sabe.logging :as logging]
            [sabe.webserver :as webserver]))

(defn prod-system []
  {:components
   {:kinesis-client (kinesis/map->KinesisClient {})
    ;; :input-logging-app (apps/map->LoggingApp {})
    ;; :output-logging-app (apps/map->LoggingApp {})
    :direct-debit-app (apps-direct-debit/map->DirectDebitApp {})
    :system-app (apps-system/map->SystemApp {})
    :webserver (webserver/map->WebServer {})
    :webserver-output-app (apps-webserver-output/map->WebServerOutputApp {})}
   :dependencies
   {:direct-debit-app [:kinesis-client]
    :system-app [:kinesis-client]
    :webserver [:kinesis-client :webserver-output-app]}})

(defn new-system
  [{:keys [components dependencies]} config]
  (-> (component/map->SystemMap components)
      (config/configure config)
      (component/system-using dependencies)))

(defn start-system
  [system options]
  ;; (logging/install-uncaught-exception-handler)
  (log/info "Starting system with options:" (dissoc options :config-key))
  (let [config (config/config options)]
    (if (contains? options :dry-run)
      (System/exit 0)
      (let [system (-> (system)
                       (new-system config)
                       (component/start))]
        (log/info "Started system")
        system))))

(defn stop-system
  [system]
  (component/stop-system system))
