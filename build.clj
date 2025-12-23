(ns build
  (:require [deps-deploy.deps-deploy :as dd]))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (assert (:version opts) ":version not specified in opts")
  (dd/deploy {:installer :remote :artifact (str "target/netty-socketio-" (:version opts) ".jar")
              :pom-file "pom.xml"})
  opts)
