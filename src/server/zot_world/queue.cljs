(ns ^:figwheel-load zot-world.server.queue
  (:require [cljs.nodejs :as nodejs]))

(defonce amqp (nodejs/require "amqplib"))

(defn edn->buffer [data]
  (js/Buffer. (pr-str data)))

(defn wrap [{:keys [connection-string queue] :as opts} f]
  (-> amqp
      (.connect connection-string)
      (.then #(.createChannel %))
      (.then (fn [chan]
               (-> chan
                   (.assertQueue queue)
                   (.then #(f chan)))))))

(defn enqueue [{:keys [data queue] :as opts}]
  (wrap
    opts
    #(.sendToQueue % queue (edn->buffer data))))

(defn consume [{:keys [handler queue] :as opts}]
  (wrap
    opts
    #(.consume % queue (partial handler %))))
