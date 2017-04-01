(ns ^:figwheel-always zot-world.server.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]
            [zot-world.server.routes :as routes]))

(nodejs/enable-util-print!)

(defonce compression (nodejs/require "compression"))
(defonce cookie-parser (nodejs/require "cookie-parser"))
(defonce express (nodejs/require "express"))
(defonce helmet (nodejs/require "helmet"))
(defonce http (nodejs/require "http"))
(defonce morgan (nodejs/require "morgan"))
(defonce static-dir (nodejs/require "serve-static"))

(defonce production? (= (.. js/process -env -NODE_ENV)
                        "production"))

;; app gets redefined on reload
(def app (-> (express)
             (.use (compression))
             (.use (helmet))
             (.use (morgan (if production?
                             "combined"
                             "dev")))
             (.use (static-dir "resources"))
             (.use (cookie-parser (.-COOKIE_SECRET (.-env js/process))))))

;; routes get redefined on each reload
(doto app
  (.use routes/router))

(def -main
  (fn []
    ;; This is the secret sauce. you want to capture a reference to
    ;; the app function (don't use it directly) this allows it to be redefined on each reload
    ;; this allows you to change routes and have them hot loaded as you
    ;; code.
    (doto (.createServer http #(app %1 %2))
      (.listen (or (.-PORT (.-env js/process)) 3000)))))

(set! *main-cli-fn* -main)
