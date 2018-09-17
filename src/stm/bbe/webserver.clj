(ns stm.bbe.webserver
  (:require
   [clojure.core.async :as a]
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [compojure.core :as compojure :refer [GET]]
   [compojure.route :as route]
   [ring.middleware.params :as params]
   [aleph.http :as http]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [stm.bbe.kinesis :as kinesis]))

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
       (fn [_] non-websocket-request))))

(defn msg-handler
  [req
   {:keys [kinesis-client input-stream]}]
  (let [client-id (-> req :params :client-id)]
    (->
     (d/let-flow [socket (http/websocket-connection req)]
       (s/consume
        #(kinesis/put (:client kinesis-client) input-stream client-id
                      ;; Assume the data is a string for now
                      (.getBytes % "UTF-8"))
        socket))
     (d/catch
         (fn [_] non-websocket-request)))))

(defn server-handler
  [config]
  (params/wrap-params
   (compojure/routes
    (GET "/echo" [] echo-handler)
    (GET "/msg/:client-id" [client-id]
         #(msg-handler % config))
    (route/not-found "No such page."))))

;; we need
;; a worker that is listening to messages on bbe-output
;; need to create a subscription to that worker's ouptput channel that
;; filters out messages with matching client-id
(defrecord WebServer
    [server port kinesis-client input-stream output-stream]
  component/Lifecycle
  (start [this]
    (assoc this :server
           (http/start-server
            (server-handler {:kinesis-client kinesis-client
                             :input-stream input-stream
                             :output-stream output-stream})
            {:port port})))

  (stop [{:keys [server] :as this}]
    (when server
      (.close server))
    (assoc this :server nil)))
