(ns sabe.webserver
  (:require
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [compojure.core :as compojure :refer [GET]]
   [compojure.route :as route]
   [ring.middleware.params :as params]
   [aleph.http :as http]
   [manifold.bus :as b]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [sabe.kinesis.client :as kinesis]))

(def non-websocket-request
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})

(defn echo-handler
  [req]
  (->
   (d/let-flow [socket (http/websocket-connection req)]
     (s/connect socket socket))
   (d/catch
       (fn [_] non-websocket-request)))
  nil)

(defn msg-handler
  [req
   {:keys [kinesis-client input-stream bus client-write-timeout]}]
  (let [client-id        (-> req :params :client-id)
        bus-subscription (b/subscribe bus client-id)]
    (log/tracef "Subscribed to bus with client-id '%s'" client-id)
    (->
     (d/let-flow [socket (http/websocket-connection req)]
       (s/on-closed socket (fn []
                             (log/tracef "Unsubscribing from bus for client-id '%s'"
                                         client-id)
                             (s/close! bus-subscription)))

       ;; websocket -> msg-input
       (s/consume (fn [msg]
                    (log/tracef "Received msg, %d bytes, putting on '%s'"
                                (count msg) input-stream)
                    (kinesis/put kinesis-client input-stream client-id msg))
                  socket)

       ;; msg-output -> websocket
       (s/connect bus-subscription socket
                  {:timeout   client-write-timeout
                   :upstream? true}))
     (d/catch
         (fn [_] non-websocket-request))))

  ;; Compojure doesn't like a boolean return value, so return nil
  nil)

(defn server-handler
  [config]
  (params/wrap-params
   (compojure/routes
    (GET "/echo" [] echo-handler)
    (GET "/message/:client-id" [client-id]
         #(msg-handler % config))
    (route/not-found "No such page."))))

(defrecord WebServer
    [server port kinesis-client input-stream webserver-output-app
     client-write-timeout]
  component/Lifecycle
  (start [this]
    (log/info "Starting WebServer")
    (assoc this :server
           (http/start-server
            (server-handler {:bus                  (:bus webserver-output-app)
                             :client-write-timeout client-write-timeout
                             :input-stream         input-stream
                             :kinesis-client       (:client kinesis-client)})
            {:port port})))

  (stop [{:keys [server] :as this}]
    (when server
      (.close server))
    (assoc this :server nil)))
