(require
  '[figwheel-sidecar.repl-api :as ra])

(defn start []
  (ra/start-figwheel!
    {:figwheel-options {}
     :build-ids ["server" "client"]
     :all-builds
     [{:id "client"
       :figwheel true
       :source-paths ["src/shared" "src/client"]
       :compiler {:asset-path "js/out"
                  :infer-externs true
                  :main "zot-world.client.core"
                  :optimizations :none
                  :output-dir "resources/js/out"
                  :output-to "resources/js/main.js"
                  :source-map true}}
      {:id "server"
       :figwheel true
       :source-paths ["src/shared" "src/server"]
       :compiler {:infer-externs true
                  :main "zot-world.server.core"
                  :optimizations :none
                  :output-dir "target/server"
                  :output-to "target/server/main.js"
                  :source-map true
                  :target :nodejs}}]}))

(defn stop []
  (ra/stop-figwheel!))

(defn repl []
  (ra/cljs-repl))
