# Celery Prometheus Exporter 

Celery Exporter is a Prometheus metrics exporter for Celery 4, written in Clojure.

Here the list of exposed metrics:

* `celery_tasks_total` exposes the number of tasks currently known to the queue labeled by `instance`, `name`, `queue` and `state`. 
* `celery_tasks_runtime_millis` tracks the number of milliseconds tasks take until completed as histogram labeled by `name`, `queue` and `instance`.
* `celery_time_spent_in_queue_millis` exposes a histogram of task latency, i.e. the time until tasks are picked up by a worker from when they are published to the queue.

---
### Compiling

```bash
lein clean
lein uberjar
```

### Running

```bash
java -jar target/uberjar/celery-prometheus-exporter.jar
```


```bash
curl "localhost:8888/scrape?target=amqp://username:password@hostname:port/vhost"
```

### Inspired by @zerok work
https://github.com/zerok/celery-prometheus-exporter
