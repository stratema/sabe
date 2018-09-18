(ns sabe.logging
  (:require [clojure.tools.logging :as log])
  (:import [org.slf4j MDC]))

(defmacro with-mdc
  [context & body]
  `(let [wrapped-context# ~context
         ctx# (MDC/getCopyOfContextMap)]
     (try
       (when (map? wrapped-context)
         (doall (map (fn [[k# v#]] (MDC/put (name k#) (str v#))) wrapped-context)))
       ~@body
       (finally
         (if ctx#
           (MDC/setContextMap ctx#)
           (MDC/clear))))))

(defn mdc-put
  [m]
  (doall (map (fn [[k v]] (MDC/put (name k) (str v))) m)))

(defn uncaught-exception-handler
  []
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ _ t]
      (log/error t (or (.getMessage t) (-> t .getClass .getName))))))

(defn install-uncaught-exception-handler
  ([]
   (install-uncaught-exception-handler (uncaught-exception-handler)))
  ([exception-handler]
   (Thread/setDefaultUncaughtExceptionHandler exception-handler)))
