(ns celery-prometheus-exporter.core 
  (:gen-class)
  (:require
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]
    [clojure.string :as string]
    [chime.core :as chime]
    [chime.core-async :refer [chime-ch]]
    [compojure.core :refer [GET defroutes]]
    [compojure.route :refer [not-found]]
    [environ.core :refer [env]]
    [iapetos.core :as prometheus]
    [iapetos.export :as export]
    [jsonista.core :as j]
    [java-time :as t]
    [medley.core :refer [dissoc-in]]
    [org.httpkit.server :refer [run-server]]
    [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
    [langohr.channel :as lch]
    [langohr.core :as rmq]
    [langohr.queue :as lq]
    [langohr.basic :as lb]
    [langohr.consumers :as lcons]))

(def task-event-to-state 
  {"task-sent" "PENDING"
   "task-received" "RECEIVED"
   "task-started" "STARTED"
   "task-failed" "FAILURE"
   "task-retried" "RETRY"
   "task-succeeded" "SUCCESS"
   "task-revoked" "REVOKED"
   "task-rejected" "REJECTED"})

(def tasks-total-counter
  (prometheus/counter :celery/tasks-total
                      {:description "Number of tasks per state and name"
                       :labels [:instance :queue :name :state]}))

(def tasks-runtime-histogram
  (prometheus/histogram :celery/tasks-runtime-millis
                        {:description "Task runtime (seconds) per queue and name"
                         :labels [:instance :queue :name]
                         :buckets [50.0 100.0 250.0 500.0 750.0 1000.0 2500.0 5000.0 7500.0 10000.0 25000.0 60000.0 180000.0 300000.0 600000.0 1200000.0]}))

(def time-spent-in-queue-histogram
  (prometheus/histogram :celery/time-spent-in-queue-millis
                        {:description "Time from when a task is published and received by a worker per queue (seconds)"
                         :labels [:instance :queue :name]
                         :buckets [1000.0 5000.0 15000.0 30000.0 45000.0 60000.0 180000.0 300000.0 600000.0 1800000.0 3600000.0 7200000.0 1.08E7 1.8E7 3.6E7]}))


(defn new-registry []
  (-> (prometheus/collector-registry)
      (prometheus/register
        tasks-total-counter
        tasks-runtime-histogram
        time-spent-in-queue-histogram)))


(defonce active-scrape-instances (atom {}))

(defonce active-events (atom {}))


(defn process-event [prom-registry instance-uri events-atom event]
  ;; add the event to the events-atom as this event contains all details of the task
  (when (= (:type event) "task-received")
    ;; tasks can be scheduled so use the eta as the published_at in those cases
    (swap! events-atom assoc-in [instance-uri (:uuid event)] (-> event
                                                                 (update :published_at #(or (:eta event) %))
                                                                 (select-keys [:queue :name :published_at]))))

  ;; the following event types can only be processed if the task-recevied for the task is in the events-atom
  (when-let [{:keys [published_at] :as original-event} (get-in @events-atom [instance-uri (:uuid event)])]
    (let [prom-labels {:instance instance-uri
                       :queue (:queue original-event)
                       :name (:name original-event)}
          prom-labels-with-state (assoc prom-labels :state (get task-event-to-state (:type event)))]

      ;; track time spent in the queue
      (when (and (= (:type event) "task-started") published_at)
        (let [task-published-at (t/instant (t/formatter :iso-offset-date-time) published_at)
              task-started-at (t/instant (* (:timestamp event) 1000))]
          (prometheus/observe (prom-registry :celery/time-spent-in-queue-millis prom-labels)
                              (t/time-between :millis task-published-at task-started-at))))

      ;; track task runtime
      (when (= (:type event) "task-succeeded")
        (prometheus/observe (prom-registry :celery/tasks-runtime-millis prom-labels)
                            (* (:runtime event) 1000)))

      ;; increase task state counter
      (prometheus/inc (prom-registry :celery/tasks-total prom-labels-with-state))))

  ;; remove the task from the events atom
  (when (contains? #{"task-succeeded" "task-rejected" "task-revoked" "task-failed"} (:type event))
    (swap! events-atom dissoc-in [instance-uri (:uuid event)])))


(defn parse-payload [payload]
  (-> payload
      (String. "UTF-8")
      (j/read-value (j/object-mapper {:decode-key-fn keyword}))))


(defn handle-events! [prom-registry instance-uri events-atom _ _ payload]
  (doseq [event (parse-payload payload)]
    (process-event prom-registry instance-uri events-atom event)))


(defn setup-celery-event-receiver! [events-atom instance-uri]
  (let [prom-registry (new-registry)
        rmq-conn (rmq/connect {:uri instance-uri
                               :requested-heartbeat 30})
        rmq-ch (lch/open rmq-conn)
        queue-name (str "celeryev.receiver." (java.util.UUID/randomUUID))]

    (log/infof "Registering new target %s" instance-uri)

    (lq/declare rmq-ch queue-name {:exclusive true
                                   :auto-delete true
                                   :durable false})

    (lq/bind rmq-ch queue-name "celeryev" {:routing-key "task.multi"})

    (lb/qos rmq-ch 1024)

    (lcons/subscribe rmq-ch 
                     queue-name
                     (partial handle-events! prom-registry instance-uri events-atom)
                     {:auto-ack true})

    {:rmq-conn rmq-conn 
     :prom-registry prom-registry}))


(defn target-metrics-scrape [instances-atom events-atom instance-uri]
  (do 
    (when (nil? (get @instances-atom instance-uri))
      (swap! instances-atom assoc instance-uri (setup-celery-event-receiver! events-atom instance-uri)))
    (swap! instances-atom assoc-in [instance-uri :last-scraped] (t/instant))
    (export/text-format (get-in @instances-atom [instance-uri :prom-registry]))))


(defn deregister-inactive-targets! [time-now instances-atom events-atom]
  (doseq [[instance-uri {:keys [rmq-conn prom-registry last-scraped]}] (filter #(>= (t/time-between :hours (:last-scraped (second %)) time-now) 1)
                                                                               @instances-atom)]
    (log/infof "Deregistering target %s since it was last scraped at %s" instance-uri last-scraped)
    (prometheus/clear prom-registry)
    (rmq/close rmq-conn)
    (swap! instances-atom dissoc instance-uri)
    (swap! events-atom dissoc instance-uri)))


(defn inactive-targets-deregister-periodic-task! [instances-atom events-atom]
  (let [chimes (chime-ch (-> (chime/periodic-seq (t/instant) (t/duration 1 :hours))
                             rest))]
    (a/go-loop []
      (when-let [time-now (a/<! chimes)]
        (deregister-inactive-targets! time-now instances-atom events-atom)
        (recur)))))


(defroutes routes
  (GET "/scrape" [target]
    (target-metrics-scrape active-scrape-instances active-events target))
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

  (let [{:keys [port] :or {port "8888"}} env]
    ;; Actually run the server
    (log/infof "Server running on port %s" port)
    (run-server app {:port (Integer/parseInt port)})

    (log/infof "Starting period task to deregister inactive targets")
    (inactive-targets-deregister-periodic-task! active-scrape-instances active-events)))
