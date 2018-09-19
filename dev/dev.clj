(ns dev
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [reloaded.repl :refer [start stop go system init reset]]
            [sabe.config :as config]
            [sabe.logging :as logging]
            [sabe.system :as system]

            [aleph.http :as http]
            [manifold.stream :as s]
            [sabe.util :as util])
  (:import [ch.qos.logback.classic Logger]
           [org.slf4j LoggerFactory]))

(defn dev-system []
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
  (set-logging-level! 'sabe :debug)
  (set-logging-level! 'dev :debug))

(defn members
  [obj]
  (->> (:members (clojure.reflect/reflect obj))
       (sort-by :name)
       (pprint/print-table)))


;; Demo ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ping-msg [client-id]
  {:message/id (java.util.UUID/randomUUID)
   :message/type :system/echo-request
   :message/data {:sent-time (System/currentTimeMillis)}
   :client/id client-id})

(defn create-mandate-msg [client-id]
  {:message/id (java.util.UUID/randomUUID)
   :message/type :direct-debit/create-mandate
   :message/data {:email "joe@bloggs.com"
                  :first-name "joe"
                  :last-name "bloggs"
                  :address {:line1 "123 street"
                            :line2 ""
                            :city "London"
                            :postcode "AB12 3CD"
                            :country-code :gb}
                  :sort-code "01-23-45"
                  :account-number "12345678"
                  :amount "£9.99"
                  :schedule {:on 5 :each :month}}
   :client/id client-id})

(comment
  ;; Silence noise from KCL
  (set-logging-level! 'com.amazonaws.services.kinesis.clientlibrary :error)

  (let [ws-base-url "ws://localhost:8080/message/"
        client-id (java.util.UUID/randomUUID)
        conn @(http/websocket-client (str ws-base-url client-id))
        msg (ping-msg client-id)]

    (let [r (time (do
                    @(s/put! conn (util/clj->msgpack msg))
                    @(s/try-take! conn ::drained 10000 ::timeout)))]
      (println (if (bytes? r)
                 (util/msgpack->clj r)
                 r)))
    (s/close! conn)))

(comment
  (def ws-base-url "ws://localhost:8080/message/")
  (def client-id (java.util.UUID/randomUUID))
  (def conn @(http/websocket-client (str ws-base-url client-id)))
  (def msg (ping-msg client-id))

  @(s/put! conn (util/clj->msgpack msg))
  (def reply @(s/take! conn))
  (println (util/msgpack->clj reply))

  (s/close! conn)
  )

(comment
  (def ws-base-url "ws://localhost:8080/echo")
  (def client-id (java.util.UUID/randomUUID))
  (def conn @(http/websocket-client ws-base-url))
  (def msg (ping-msg client-id))

  @(s/put! conn (util/clj->msgpack msg))
  (def reply @(s/take! conn))
  (println (util/msgpack->clj reply))

  (s/close! conn)
  )
