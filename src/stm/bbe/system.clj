(ns stm.bbe.system
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [stm.bbe.config :as config]
            [stm.bbe.kinesis :as kinesis]
            [stm.bbe.logging :as logging]
            [stm.bbe.webserver :as webserver]))


(defn prod-system []
  {:components
   {:kinesis-client (kinesis/map->KinesisClient {})
    :webserver (webserver/map->WebServer {})}
   :dependencies
   {:webserver [:kinesis-client]}})

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
