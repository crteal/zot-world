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
    (-> (s3. #js {:accessKeyId (.. js/process -env -AWS_ACCESS_KEY)
                  :secretAccessKey (.. js/process -env -AWS_SECRET_ACCESS_KEY)})
        (.upload (clj->js {:Body stream
                           :Bucket (.. js/process -env -AWS_S3_MEDIA_BUCKET)
                           :ContentType content-type
                           :Key (str post-id "/" k)})
                 cb))
    stream))

(defn upload-post-media [post-id k url content-type]
  (js/Promise.
    (fn [res rej]
      (pipe-url
        url
        (upload-stream
          post-id
          k
          content-type
          (fn [err data]
            (if-not (nil? err)
              (rej err)
              (res data))))))))

(defn upload-post [post]
  (let [data (:data post)
        p (map #(upload-post-media
                  (:id post)
                  %
                  (get data (keyword (str "MediaUrl" %)))
                  (get data (keyword (str "MediaContentType" %))))
               (range (js/parseInt (:NumMedia data))))]
    (.all js/Promise (clj->js p))))

(def -main
  (fn []
    (queue/consume
      {:queue (.. js/process -env -POST_QUEUE_NAME)
       :connection-string (.. js/process -env -RABBITMQ_BIGWIG_RX_URL)
       :handler (fn [chan msg]
                  (when-not (nil? msg)
                    (-> (upload-post
                          (reader/read-string
                            (-> msg
                                .-content
                                .toString)))
                        (.then #(.ack chan msg))
                        (.catch #(.error js/console %)))))})))

(set! *main-cli-fn* -main)
