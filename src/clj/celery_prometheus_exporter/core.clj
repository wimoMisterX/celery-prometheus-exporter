(ns celery-prometheus-exporter.core 
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [compojure.core :refer [DELETE GET PUT POST defroutes context]]
    [compojure.route :refer [not-found resources]]
    [environ.core :refer [env]]
    [org.httpkit.server :refer [run-server]]
    [ring.middleware.defaults :refer [wrap-defaults api-defaults]]))

(defroutes routes
  (not-found "Not Found"))


(def app
  (-> #'routes
      (wrap-defaults api-defaults)))


(defn -main
  [& args]

  ;; Log any uncaught exceptions on secondary threads
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread)))))

  (let [{:keys [port]} env]
    ;; Actually run the server
    (log/infof "Server running on port %s" port)
    (run-server app {:port (Integer/parseInt port)})))
