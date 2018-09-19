(ns sabe.util
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cognitect.transit :as transit])
  (:import [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]
           [com.cognitect.transit
            TransitFactory
            TransitFactory$Format
            Reader
            Writer]))

(defn to-keyword
  [s]
  (when (string? s)
    (-> (string/replace s ":" "")
        string/lower-case
        keyword)))

(defn to-keyword-seq
  [ss]
  (->> ss (re-seq #"[-\w]+") (map to-keyword)))

(defn flatten-keys
  ([m]
   (flatten-keys m []))
  ([m ks]
   (reduce (fn [all [k v]]
             (let [ks (conj ks k)]
               (if (map? v)
                 (merge all (flatten-keys v ks))
                 (assoc all ks v))))
           {} m)))

(defn deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn string->input-stream
  "Aero only accepts a file object as input so we turn this string into
  an input-stream"
  [^String s]
  (-> s (.getBytes "UTF-8") io/input-stream))

(defn render
  [tpl vals]
  (string/replace tpl #"\{([-\w]+)\}" (comp str vals keyword second)))

(defn clj->msgpack [m]
  (let [out (ByteArrayOutputStream. 1024)
        writer (transit/writer out :msgpack)]
    (transit/write writer m)
    (.toByteArray out)))

(defn msgpack->clj [bytes]
  (-> (ByteArrayInputStream. bytes)
      (transit/reader :msgpack)
      (transit/read)))

(defn msgpack->java [bytes]
  (let [in (ByteArrayInputStream. bytes)
        reader (TransitFactory/reader TransitFactory$Format/MSGPACK in)]
    (.read reader)))

;; (= m (-> m clj->msgpack msgpack->clj))
