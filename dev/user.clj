(ns user)

(set! *warn-on-reflection* true)

(defn dev []
  (require 'dev)
  (in-ns 'dev))
