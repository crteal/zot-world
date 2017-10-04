(ns ^:figwheel-always zot-world.worker.core
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.reader :as reader]
    [zot-world.server.db :as db]
    [zot-world.server.email :as email]
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

(defn notify-comment-thread [{:keys [author body post]}]
  (.then
    (db/tx
      (fn [client]
        (.then
          (db/query :sites
            {:limit 1
             :client client
             :where {:id (:site_id post)}})
          (fn [site]
            (.then
              (db/users-in
                client
                (disj (reduce
                        (fn [m {:keys [author_id]}]
                          (conj m author_id))
                        #{}
                        (:comments post))
                      (:id author)))
              (fn [participants]
                {:site site :participants participants}))))))
    (fn [{:keys [site participants]}]
      (.all js/Promise
        (clj->js
          (map
            (fn [participant]
              (email/send
                {:subject (str "💬 on " (:title site))
                 :to [(:email participant)]
                 :text (str (:username author)
                            " wrote: \n\n"
                            body
                            "\n\nJoin the conversation at "
                            (str (.. js/process -env -SYSTEM_BASE_URL) "/posts/" (:id post)))}))
            participants))))))

(defmulti message-handler
  (fn [msg] (:type msg)))

(defmethod message-handler :default
  [msg]
  (js/Promise.
    (fn [res rej]
      (rej (str "Unrecognized Message Type"
                "\n"
                (pr-str msg))))))

(defmethod message-handler :post/upload
  [{:keys [data] :as msg}]
  (upload-post data))

(defmethod message-handler :notification/comment
  [{:keys [data] :as msg}]
  (notify-comment-thread data))

(def -main
  (fn []
    (queue/consume
      {:queue (.. js/process -env -SYSTEM_QUEUE_NAME)
       :connection-string (.. js/process -env -RABBITMQ_BIGWIG_RX_URL)
       :handler (fn [chan msg]
                  (when-not (nil? msg)
                    (-> (message-handler
                          (reader/read-string
                            (-> msg
                                .-content
                                .toString)))
                        (.then #(.ack chan msg))
                        (.catch #(.error js/console %)))))})))

(set! *main-cli-fn* -main)
