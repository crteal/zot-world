(defproject zot-world "0.1.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :dependencies [[cljsjs/moment             "2.17.1-1"]
                 [cljsjs/react              "15.6.1-1"]
                 [cljsjs/react-dom          "15.6.1-1"]
                 [cljsjs/react-dom-server   "15.6.1-1"]
                 [cljsjs/twemoji            "2.3.0-0"]
                 [garden                    "1.3.2"]
                 [markdown-clj              "0.9.99"]
                 [org.clojure/clojure       "1.9.0-alpha17"]
                 [org.clojure/clojurescript "1.9.671"]
                 [org.omcljs/om             "1.0.0-beta1" :exclusions [cljsjs/react cljsjs/react-dom]]]
  :plugins [[lein-cljsbuild "1.1.7"]]
  :clean-targets ^{:protect false} ["resources/js/out"
                                    "resources/js/main.js"
                                    "target"]
  :cljsbuild {
    :builds {
      :client {:source-paths ["src/shared"
                              "src/client"]
               :compiler {:asset-path "js/out"
                          :infer-externs true
                          :optimizations :none
                          :output-dir "resources/js/out"
                          :output-to "resources/js/main.js"
                          :source-map "resources/js/main.js.map"}}
      :worker {:source-paths ["src/shared"
                              "src/server"
                              "src/worker"]
               :compiler {:infer-externs true
                          :main zot-world.worker.core
                          :optimizations :none
                          :output-dir "target/worker"
                          :output-to "target/worker/main.js"
                          :target :nodejs}}
      :server {:source-paths ["src/shared"
                              "src/server"]
               :compiler {:infer-externs true
                          :main zot-world.server.core
                          :optimizations :none
                          :output-dir "target/server"
                          :output-to "target/server/main.js"
                          :source-map true
                          :target :nodejs}}}}
  :profiles {
    :dev {
      :dependencies [[figwheel-sidecar "0.5.13"]]}
    :production {
      :cljsbuild {
        :builds {
          :client {:compiler {:optimizations :advanced}}
          :worker {:compiler {:source-map false}}
          :server {:compiler {:source-map false}}}}}})
