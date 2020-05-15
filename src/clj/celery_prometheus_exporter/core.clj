(ns celery-prometheus-exporter.core 
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [compojure.core :refer [DELETE GET PUT POST defroutes context]]
    [compojure.route :refer [not-found resources]]
    [environ.core :refer [env]]
    [iapetos.core :as prometheus]
    [jsonista.core :as j]
    [iapetos.core :as prometheus]
    [iapetos.collector.ring :as ring]
    [java-time :as t]
    [org.httpkit.server :refer [run-server]]
    [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
    [langohr.channel :as lch]
    [langohr.core :as rmq]
    [langohr.exchange :as le]
    [langohr.queue :as lq]
    [langohr.basic :as lb]
    [langohr.consumers :as lcons]
    [langohr.shutdown :as lh]))

(def task-event-to-state {"task-sent" "PENDING"
                          "task-received" "RECEIVED"
                          "task-started" "STARTED"
                          "task-failed" "FAILURE"
                          "task-retried" "RETRY"
                          "task-succeeded" "SUCCESS"
                          "task-revoked" "REVOKED"
                          "task-rejected" "REJECTED"})

(defonce registry
  (-> (prometheus/collector-registry) 
      (prometheus/register 
        (prometheus/counter :celery/tasks-by-name 
                            {:description "Number of tasks per state and name"
                             :labels [:vhost :queue :name :state]})
        (prometheus/histogram :celery/tasks-runtime-millis
                              {:description "Task runtime (seconds) per queue and name"
                               :labels [:vhost :queue :name]
                               :buckets [50.0 100.0 250.0 500.0 750.0 1000.0 2500.0 5000.0 7500.0 10000.0 25000.0 60000.0 180000.0 300000.0 600000.0 1200000.0]})
        (prometheus/histogram :celery/time-spent-in-queue-millis
                              {:description "Time from when a task is published and received by a worker per queue (seconds)"
                               :labels [:vhost :queue :name]
                               :buckets [1000.0 30000.0 60000.0 180000.0 300000.0 600000.0 1800000.0 3600000.0 7200000.0 1.08E7 1.8E7 3.6E7]}))
      (ring/initialize)))

(defn process-event [vhost vhost-registry events-atom event]
  (when (= (:type event) "task-received")
    (swap! events-atom assoc (:uuid event) event))

  (let [{:keys [queue name published_at]} (get @events-atom (:uuid event))]
    (when (= (:type event) "task-started")
      (let [task-published-at (some->> published_at
                                       (t/instant (t/formatter :iso-offset-date-time)))
            task-started-at (t/instant (* (:timestamp event) 1000))]
        (when (and task-published-at task-started-at)
          (prometheus/observe (vhost-registry :celery/time-spent-in-queue-millis {:vhost vhost 
                                                                                  :queue queue 
                                                                                  :name name})
                              (t/time-between :millis task-published-at task-started-at)))))
    (when (= (:type event) "task-succeeded")
      (prometheus/observe (vhost-registry :celery/tasks-runtime-millis {:vhost vhost 
                                                                        :queue queue 
                                                                        :name name})
                          (* (:runtime event) 1000)))
    (prometheus/inc (vhost-registry :celery/tasks-by-name {:vhost vhost 
                                                           :queue queue 
                                                           :name name 
                                                           :state (get task-event-to-state (:type event))})))

  (when (contains? #{"task-succeeded" "task-rejected" "task-revoked" "task-failed"} (:type event))
    (swap! events-atom dissoc (:uuid event))))

(defn handle-events! [vhost vhost-registry events-atom rmq-ch {:keys [delivery-tag]} payload]
  (doseq [event (-> payload
                    (String. "UTF-8")
                    (j/read-value (j/object-mapper {:decode-key-fn keyword})))]
    (process-event vhost vhost-registry events-atom event)))


(defn setup-celery-event-receiver! [vhost]
  (let [rmq-conn (rmq/connect {:uri (str "amqp://siquser:siqpassword@localhost:5672/" vhost)})
        rmq-ch (lch/open rmq-conn)
        queue-name (str "celeryev.receiver." (java.util.UUID/randomUUID))
        event-atom (atom {})]

    (lq/declare rmq-ch queue-name {:exclusive true
                                   :auto-delete true
                                   :durable false})

    (lq/bind rmq-ch queue-name "celeryev" {:routing-key "task.multi"})

    (lb/qos rmq-ch 1024)

    (lcons/subscribe rmq-ch 
                     queue-name
                     (partial handle-events! vhost registry event-atom)
                     {:auto-ack true})
    
    ))


(defroutes routes
  (not-found "Not Found"))


(def app
  (-> #'routes
      (wrap-defaults api-defaults)
      (ring/wrap-metrics registry {:path "/metrics"})))


(defn -main
  [& args]

  ;; Log any uncaught exceptions on secondary threads
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread)))))

  (let [{:keys [port]} env]
    (setup-celery-event-receiver! "siqrabbit")
    ;; Actually run the server
    (log/infof "Server running on port %s" port)
    (run-server app {:port (Integer/parseInt port)})))
