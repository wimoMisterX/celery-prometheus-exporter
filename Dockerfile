FROM clojure
COPY target/uberjar/celery-prometheus-exporter.jar .
EXPOSE 6999 
CMD ["java", "-jar", "celery-prometheus-exporter.jar"]
