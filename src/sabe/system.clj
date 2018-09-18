(ns sabe.system
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sabe.config :as config]
            [sabe.kinesis.client :as client]
            [sabe.kinesis.consumers :as consumers]
            [sabe.logging :as logging]
            [sabe.webserver :as webserver]))

(defn prod-system []
  {:components
   {:kinesis-client (client/map->KinesisClient {})
    :system-consumer (consumers/map->SystemConsumer {})
    ;; :input-logging-consumer (consumers/map->LoggingConsumer {})
    ;; :output-logging-consumer (consumers/map->LoggingConsumer {})
    :webserver (webserver/map->WebServer {})
    :webserver-output-consumer (consumers/map->WebServerOutputConsumer {})}
   :dependencies
   {:webserver [:kinesis-client :webserver-output-consumer]
    :webserver-output-consumer [:kinesis-client]
    ;; :input-logging-consumer [:kinesis-client]
    ;; :output-logging-consumer [:kinesis-client]
    :system-consumer [:kinesis-client]
    }})

(defn new-system
  [{:keys [components dependencies]} config]
  (-> (component/map->SystemMap components)
      (config/configure config)
      (component/system-using dependencies)))

(defn start-system
  [system options]
  (logging/install-uncaught-exception-handler)
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
