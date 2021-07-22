FROM clojure:openjdk-11-lein as builder
WORKDIR /app
COPY project.clj project.clj
RUN lein deps
COPY . .
RUN lein uberjar

FROM openjdk:11-jre-slim as final
COPY --from=builder /app/target/uberjar/celery_exporter.jar /bin/celery_exporter.jar
ENTRYPOINT ["java", "-jar", "/bin/celery_exporter.jar"]
