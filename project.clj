(defproject zot-world "0.1.0-SNAPSHOT"
  :min-lein-version "2.7.1"

  :dependencies [[cljsjs/moment               "2.17.1-1"]
                 [cljsjs/react                "16.2.0-3"]
                 [cljsjs/react-dom            "16.2.0-3"]
                 [cljsjs/react-dom-server     "16.2.0-3"]
                 [cljsjs/twemoji              "2.4.0-0"]
                 [com.cognitect/transit-cljs  "0.8.243"]
                 [garden                      "1.3.3"]
                 [markdown-clj                "1.0.2"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure         "1.9.0-alpha19"]
                 [org.clojure/clojurescript   "1.9.671"]
                 [org.omcljs/om               "1.0.0-beta2"
                  :exclusions [cljsjs/react
                               cljsjs/react-dom
                               com.cognitect/transit-cljs]]]

  :plugins [[lein-figwheel "0.5.14"]
            [lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]]

  :source-paths ["src"]

  :cljsbuild {
    :builds {
      :client {:source-paths ["src/shared"
                              "src/client"]
               :compiler {:asset-path "/js/out"
                          :language-in :ecmascript5
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
                          :language-in :ecmascript5
                          :main zot-world.server.core
                          :optimizations :none
                          :output-dir "target/server"
                          :output-to "target/server/main.js"
                          :source-map true
                          :target :nodejs}}}}
  :profiles {
    :dev {
      :dependencies [[binaryage/devtools "0.9.9"]
                     [figwheel-sidecar "0.5.14"]
                     [com.cemerick/piggieback "0.2.2"]]
      :source-paths ["src" "dev"]
      :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
      :clean-targets ^{:protect false} ["resources/js/out"
                                        "resources/js/main.js"
                                        :target-path]}
    :production {
      :cljsbuild {
        :builds {
          :client {:compiler {:optimizations :advanced}}
          :worker {:compiler {:source-map false}}
          :server {:compiler {:source-map false}}}}}})
