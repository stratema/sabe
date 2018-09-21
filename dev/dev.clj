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
            [manifold.bus :as b]
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

(def pp pprint/pprint)

(defn ping-msg [client-id]
  {:message/id (java.util.UUID/randomUUID)
   :message/type :system/echo-request
   :message/data {:sent-time (System/currentTimeMillis)}
   :client/id client-id})

(defn create-msg [client-id type data]
  ;; TODO: Authorisation and signature
  {:message/id (java.util.UUID/randomUUID)
   :message/type type
   :message/data data
   :client/id client-id})

(defn send-and-wait [conn msg]
  @(s/put! conn (util/clj->msgpack msg))
  (let [reply @(s/try-take! conn ::drained 10000 ::timeout)]
    (if (bytes? reply)
      (util/msgpack->clj reply)
      reply)))

(comment
  ;; Silence noise from Kinesis/KCL
  (set-logging-level! 'com.amazonaws.services.kinesis :error)
  )

(comment

  ;; Test the WebServer socket ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (let [conn @(http/websocket-client "ws://localhost:8080/echo")
        msg (ping-msg (java.util.UUID/randomUUID))]

    @(s/put! conn (util/clj->msgpack msg))
    (println (util/msgpack->clj @(s/take! conn)))
    (s/close! conn))
  )

(comment

  ;; Ping! ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (let [ws-base-url "ws://localhost:8080/message/"
        client-id (java.util.UUID/randomUUID)
        conn @(http/websocket-client (str ws-base-url client-id))]
    (set-logging-level! 'sabe :error)
    (dotimes [_ 4]
      (let [msg (ping-msg client-id)
            reply (send-and-wait conn msg)
            now (System/currentTimeMillis)]
        (if (map? reply)
          (let [{:keys [sent-time received-time]} (:message/data reply)]
            (log/infof "outward=%dms return=%dms total=%dms"
                       (- received-time sent-time)
                       (- now received-time)
                       (- now sent-time)))
          (log/info "Failed:" reply))))

    (s/close! conn))
  )

(comment

  ;; Setup a direct debit ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (set-logging-level! 'sabe :trace)
  (def client-id (java.util.UUID/randomUUID))
  (def conn @(http/websocket-client (str "ws://localhost:8080/message/" client-id)))
  (def data {:email "joe@bloggs.com"
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
             :schedule {:on 5 :each :month}})

  (def msg (create-msg client-id :direct-debit/create-mandate data))

  (pp msg)

  (def reply (send-and-wait conn msg))

  (if (map? reply)
    (log/info "Success:" reply)
    (log/info "Failed:" reply))

  (pp reply)

  (pp (-> system :direct-debit-app :mandate-db deref))

  (def mandate (:message/data reply))

  ;; Cancel a direct debit ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (def msg (create-msg client-id :direct-debit/cancel-mandate
                       {:mandate/id (:mandate/id mandate)
                        :country-code (-> mandate :mandate/details
                                          :address :country-code)}))
  (pp msg)
  (def reply (send-and-wait conn msg))
  (if (map? reply)
    (log/info "Success:" reply)
    (log/info "Failed:" reply))

  (pp (-> system :direct-debit-app :mandate-db deref keys))

  (s/close! conn)
  )
