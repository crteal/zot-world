(ns ^:figwheel-always zot-world.worker.core
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.reader :as reader]
    [zot-world.server.db :as db]
    [zot-world.server.email :as email]
    [zot-world.server.queue :as queue]
    [zot-world.server.utils :as utils :refer [get-env]]))

(nodejs/enable-util-print!)

(defonce request (nodejs/require "request"))
(defonce s3 (nodejs/require "aws-sdk/clients/s3"))
(defonce PassThrough (.-PassThrough (nodejs/require "stream")))

(defn pipe-url [url stream]
  (-> (request url)
      (.pipe stream)))

(defn upload-stream [post-id k content-type cb]
  (let [stream (PassThrough.)]
    (-> (s3. #js {:accessKeyId (get-env "AWS_ACCESS_KEY")
                  :secretAccessKey (get-env "AWS_SECRET_ACCESS_KEY")})
        (.upload (clj->js {:Body stream
                           :Bucket (get-env "AWS_S3_MEDIA_BUCKET")
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

(defn user-by-id [client id]
  (db/query :users
    {:limit 1
     :client client
     :where {:id id}}))

(defn send-email! [email-type {:keys [to] :as config}]
  (println "send-email!" email-type to)
  (.then (db/users-by-emails to)
         (fn [users]
           (let [filtered (filter (fn [{:keys [settings]}]
                                    (get-in settings [:email email-type]))
                                  users)]
             (println "filtered" filtered)
             (when-not (empty? filtered)
               (email/send (merge config {:to (map :email filtered)})))))))

(defn notify-applause [{:keys [fan post]}]
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
              (.all js/Promise #js [(user-by-id client (:author_id post))
                                    (user-by-id client (:id fan))])
              (fn [[author fan]]
                {:site site :author author :fan fan}))))))
    (fn [{:keys [site author fan]}]
      (send-email! :applause
        {:subject (str "😍 on " (:title site) " from " (:username fan))
         :to [(:email author)]
         :text (str "Check out the post at "
                    (str (get-env "SYSTEM_BASE_URL") "/posts/" (:id post)))}))))

(defn notify-comment-thread [{:keys [author body post]}]
  (.then
    (db/tx
      (fn [client]
        (.then
          (js/Promise.all
            #js [(db/query :sites
                   {:limit 1
                    :client client
                    :where {:id (:site_id post)}})
                 (db/post-participants client (:id post))])
          (fn [[site participants]]
            (merge {:site site}
                   (reduce
                     (fn [m {:keys [id] :as participant}]
                       (if (= id (:id author))
                         (assoc m :author participant)
                         (merge-with conj m {:participants participant})))
                     {:participants []}
                     participants))))))
    (fn [{:keys [author participants site]}]
      (.all js/Promise
        (clj->js
          (map
            (fn [participant]
              (send-email! :conversation
                {:subject (str "💬 on " (:title site))
                 :to [(:email participant)]
                 :text (str (:username author)
                            " wrote: \n\n"
                            body
                            "\n\nJoin the conversation at "
                            (str (get-env "SYSTEM_BASE_URL") "/posts/" (:id post)))}))
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

(defmethod message-handler :notification/applause
  [{:keys [data] :as msg}]
  (notify-applause data))

(defmethod message-handler :notification/comment
  [{:keys [data] :as msg}]
  (notify-comment-thread data))

(def -main
  (fn []
    (queue/consume
      {:queue (get-env "SYSTEM_QUEUE_NAME")
       ;; TODO remove RabbitMQ specific information once migrated
       :connection-string (get-env "SYSTEM_QUEUE_URL"
                                   (get-env "RABBITMQ_BIGWIG_RX_URL"))
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
