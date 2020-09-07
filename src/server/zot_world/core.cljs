(ns ^:figwheel-always zot-world.server.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]
            [zot-world.server.routes :as routes]))

(nodejs/enable-util-print!)

(defonce compression (nodejs/require "compression"))
(defonce connect-redis (nodejs/require "connect-redis"))
(defonce express (nodejs/require "express"))
(defonce helmet (nodejs/require "helmet"))
(defonce http (nodejs/require "http"))
(defonce morgan (nodejs/require "morgan"))
(defonce redis (nodejs/require "redis"))
(defonce session (nodejs/require "express-session"))
(defonce RedisStore (connect-redis session))

(defonce production? (= (.. js/process -env -NODE_ENV)
                        "production"))

;; by default `compression` will not work for EDN
(defn compression-filter
  [req res]
  (if-some [content-type (.getHeader res "Content-Type")]
    (or (some? (re-find #"application/edn" content-type))
        (.filter compression req res))
    (.filter compression req res)))

(defonce redis-client
  (doto (.createClient redis #js {:url (.-REDIS_URL (.-env js/process))})
    (.unref)
    (.on "error" js/console.error)))

;; app gets redefined on reload
(def app (-> (express)
             (.use (compression #js {:filter compression-filter}))
             (.use (helmet #js {:contentSecurityPolicy false}))
             (.use (morgan (if production?
                             "combined"
                             "dev")))
             (.use (.static express "resources"))
             (.use (session (clj->js
                              {:cookie {:httpOnly true
                                        :maxAge 3600000}
                               :resave false
                               :rolling true
                               :saveUninitialized false
                               :store (RedisStore. #js {:client redis-client})
                               :secret (.-COOKIE_SECRET (.-env js/process))})))))

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
