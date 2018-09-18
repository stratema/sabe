(ns stm.bbe.kinesis.util
  (:require [clojure.string :as string])
  (:import [com.amazonaws ClientConfiguration]))

(defn unique-client-id
  ([] (unique-client-id (str (java.net.InetAddress/getLocalHost))))
  ([ip-address] (str ip-address ":" (java.util.UUID/randomUUID))))

(defn client-configuration
  [name version]
  (doto (ClientConfiguration.)
    (.setUserAgentPrefix
     (string/join [ClientConfiguration/DEFAULT_USER_AGENT " " name "/" version]))
    (.setUserAgentSuffix nil)))
