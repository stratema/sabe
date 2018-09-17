(ns stm.bbe.util
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

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
