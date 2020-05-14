(defproject celery-prometheus-exporter "0.1.0"
  :description "Celery Prometheus Exporter"

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.1.587"]
                 [org.clojure/tools.logging "1.1.0"]
                 [ch.qos.logback/logback-classic "1.1.3"]

                 [environ "1.2.0"]

                 [compojure "1.6.1" :exclusions [ring/ring-codec]]
                 [http-kit "2.3.0"]
                 [ring/ring-core "1.8.0" :exclusions [commons-codec]]
                 [ring/ring-defaults "0.3.2"]
                 
                 [com.novemberain/langohr "5.1.0"]
                 [metosin/jsonista "0.2.6"]]

  :plugins [[lein-ring "0.12.5"]]

  :source-paths ["src/clj"]

  :target-path "target/%s/"

  :main ^:skip-aot celery-prometheus-exporter.core

  :uberjar-name "celery-prometheus-exporter.jar"

  :clean-targets ^{:protect false} [:target-path]

  :profiles {:dev [:project/dev :profiles/dev]

             :project/dev {:dependencies [[ring/ring-devel "1.8.0" :exclusions [commons-codec]]]}

             :profiles/dev {}

             :uberjar {:prep-tasks ["compile"]
                       :aot :all
                       :omit-source true}})
