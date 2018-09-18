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
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

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
   {:keys [kinesis-client input-stream webserver-output-consumer
           client-write-timeout]}]
  (let [client-id (-> req :params :client-id)
        bus (:bus webserver-output-consumer)]
    (->
     (d/let-flow [socket (http/websocket-connection req)]
       (s/consume
        #(kinesis/put (:client kinesis-client) input-stream client-id %)
        (s/throttle 10 socket))

       (s/connect
        (b/subscribe bus client-id)
        socket
        {:timeout client-write-timeout}))
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
    [server port kinesis-client input-stream webserver-output-consumer
     client-write-timeout]
  component/Lifecycle
  (start [this]
    (assoc this :server
           (http/start-server
            (server-handler {:kinesis-client kinesis-client
                             :input-stream input-stream
                             :client-write-timeout client-write-timeout
                             :webserver-output-consumer webserver-output-consumer})
            {:port port})))

  (stop [{:keys [server] :as this}]
    (when server
      (.close server))
    (assoc this :server nil)))
