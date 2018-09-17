(ns stm.bbe.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [stm.bbe.crypt :as crypt]
            [stm.bbe.util :as util])
  (:import [java.io StringWriter]))

(defmethod aero/reader 'region
  [{:keys [region]} _ value]
  (cond (contains? value region) (get value region)
        (contains? value :default) (get value :default)
        :otherwise nil))

(defmethod aero/reader 'regex
  [_ _ value]
  (re-pattern value))

(defmethod aero/reader 'decrypt
  [{:keys [config-key decrypt]} _ value]
  (try
    (if decrypt
      (crypt/decrypt-from-base64 value config-key)
      value)
    (catch Exception e
      (log/fatal "Could not decrypt value, did you set the correct config key?")
      (throw e))))

(defn output-settings
  [config]
  (let [out (StringWriter.)
        kvs (->> config util/flatten-keys (sort-by key))
        kwidth (some->> (keys kvs) (map (comp count str)) (reduce max))]
    (doseq [[k v] kvs]
      (pprint/cl-format out (str "~" kwidth "A ~A\n") k v))
    (str out)))

(defn config
  [{:keys [config-key config-file profile region
           master-config-file restore-last-backup]
    :or {profile :dev region :nam
         config-key (System/getenv "CONFIG_KEY")
         master-config-file "config.edn"
         restore-last-backup false}}]
  (when (nil? config-key)
    (log/warn "Config key is nil, can't decrypt secrets"))

  (some->> profile name string/upper-case (System/setProperty "PROFILE"))
  (some->> region name string/upper-case (System/setProperty "REGION"))
  (System/setProperty "RESTORE_LAST_BACKUP" (str restore-last-backup))

  (let [options {:config-key config-key :decrypt true
                 :profile profile :region region}
        config (-> master-config-file io/resource (aero/read-config options))
        override (some-> config-file io/file (aero/read-config options))]
    (log/infof "Configuration:\n%s"
               (-> master-config-file
                   io/resource
                   (aero/read-config (assoc options :decrypt false))
                   output-settings))
    (when override
      (log/infof "Overriding master config with %s:\n%s"
                 config-file
                 (-> config-file
                     io/file
                     (aero/read-config (assoc options :decrypt false))
                     output-settings)))
    (util/deep-merge config (or override {}))))

(defn configure
  [system config]
  (merge-with merge system config))
