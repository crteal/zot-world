(ns ^:figwheel-always zot-world.worker.core
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.reader :as reader]
    [zot-world.server.queue :as queue]))

(nodejs/enable-util-print!)

(defonce request (nodejs/require "request"))
(defonce s3 (nodejs/require "aws-sdk/clients/s3"))
(defonce PassThrough (.-PassThrough (nodejs/require "stream")))

(defn pipe-url [url stream]
  (-> (request url)
      (.pipe stream)))

(defn upload-stream [post-id k content-type cb]
  (let [stream (PassThrough.)]
    (-> (s3.)
        (.upload (clj->js {:Body stream
                           :Bucket (.. js/process -env -S3_MEDIA_BUCKET)
                           :ContentType content-type
                           :Key (str post-id "/" k)})
                 cb))
    stream))

(defn upload-post-media [post-id k url content-type cb]
  (pipe-url url (upload-stream post-id k content-type cb)))

(defn upload-post [post cb]
  (dotimes [n (js/parseInt (get-in post [:data :NumMedia]))]
    (upload-post-media
      (:id post)
      n
      (get-in post [:data (keyword (str "MediaUrl" n))])
      (get-in post [:data (keyword (str "MediaContentType" n))])
      ;; TODO this is flat out wrong
      #(when (= 0 n) (cb)))))

(def -main
  (fn []
    (queue/consume
      {:queue (.. js/process -env -POST_QUEUE_NAME)
       :connection-string (.. js/process -env -RABBITMQ_BIGWIG_RX_URL)
       :handler (fn [chan msg]
                  (when-not (nil? msg)
                    (upload-post
                      (reader/read-string
                        (-> msg
                            .-content
                            .toString))
                      #(.ack chan msg))))})))

(set! *main-cli-fn* -main)
