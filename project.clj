(defproject zot-world "0.1.0-SNAPSHOT"
  :min-lein-version "2.7.1"

  :dependencies [[cljsjs/moment               "2.24.0-0"]
                 [cljsjs/react                "16.8.6-0"]
                 [cljsjs/react-dom            "16.8.6-0"]
                 [cljsjs/react-dom-server     "16.8.6-0"]
                 [cljsjs/twemoji              "11.2.0-0"]
                 [com.birdduck/hom            "0.1.0-SNAPSHOT"]
                 [com.cognitect/transit-cljs  "0.8.256"]
                 [garden                      "1.3.9"]
                 [markdown-clj                "1.10.0"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure         "1.10.0"]
                 [org.clojure/clojurescript   "1.10.520"]
                 [org.omcljs/om               "1.0.0-beta4"
                  :exclusions [cljsjs/react
                               cljsjs/react-dom
                               com.cognitect/transit-cljs]]]

  :plugins [[lein-figwheel "0.5.18"]
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
      :dependencies [[binaryage/devtools "0.9.10"]
                     [figwheel-sidecar "0.5.18"]
                     [cider/piggieback "0.4.1"]]
      :source-paths ["src" "dev"]
      :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}
      :clean-targets ^{:protect false} ["resources/js/out"
                                        "resources/js/main.js"
                                        :target-path]}
    :production {
      :cljsbuild {
        :builds {
          :client {:compiler {:optimizations :advanced}}
          :worker {:compiler {:source-map false}}
          :server {:compiler {:source-map false}}}}}})
