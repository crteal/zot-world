(ns ^:figwheel-load zot-world.server.routes
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader :as reader]
            [om.next :as om]
            [om.dom :as dom]
            [cljsjs.react.dom.server]
            [clojure.string :as str]
            [goog.object :as gobj]
            [zot-world.components :as components]
            [zot-world.server.db :as db]
            [zot-world.server.middleware :as middleware]
            [zot-world.styles :as styles]
            [zot-world.server.queue :as queue]))

(defonce bcrypt (nodejs/require "bcrypt"))
(defonce express (nodejs/require "express"))
(defonce jwt (nodejs/require "jsonwebtoken"))
(defonce s3 (nodejs/require "aws-sdk/clients/s3"))
(defonce twilio (nodejs/require "twilio"))

(def *default-post-page-size* 5)

(defn production? []
  (= (.. js/process -env -NODE_ENV)
     "production"))

(defonce serializers {:css {:headers {:Content-Type "text/css"}}
                      :edn {:headers {:Content-Type "application/edn"}
                            :serializer #(binding [*print-namespace-maps* false]
                                           (pr-str %))}
                      :twiml {:headers {:Content-Type "text/xml"}
                              :serializer #(.toString %)}})

(defn make-send [serializer-configurations]
  (fn [res content-type data]
    (let [config (get serializer-configurations content-type)
          serializer (get config :serializer identity)]
      (-> res
          (cond->
            (contains? config :headers) (.set (clj->js (:headers config))))
          (.send (serializer data))))))

(def send! (make-send serializers))

; helpers
(defn render! [res component props]
  (.send res (dom/render-to-str (component props))))

(defn enqueue! [t data]
  (queue/enqueue
    {:data {:type t :data data}
     :queue (.. js/process -env -SYSTEM_QUEUE_NAME)
     :connection-string (.. js/process -env -RABBITMQ_BIGWIG_TX_URL)}))

(defn site-page-renderer [f]
  (fn [req res]
    (.then
      (db/tx
        (fn [client]
          (.then
            (db/query :sites
              {:limit 1
               :client client
               :where {:slug (.. js/process -env -ZOT_WORLD_SINGLE_TENANT_SLUG)}})
            #(f req client %))))
    (fn [{:keys [site posts]}]
      (render!
        res
        (components/app-page
          (:title site)
          (components/data {:post-page-size *default-post-page-size*
                            :posts posts
                            :site (select-keys site [:id])
                            :user {:id (.. req -session -userId)}}))
        {:title (:title site)})))))

; routes
(def index
  (site-page-renderer
    (fn [_ client site]
      (.then
        (db/query :posts
          {:limit *default-post-page-size*
           :client client
           :where {:site_id (:id site)}})
        #(merge {:site site} {:posts %})))))

(def post-page
  (site-page-renderer
    (fn [req client site]
      (.then
        (db/query :posts
          {:limit 1
           :client client
           :where {:id (.. req -params -id)
                   :site_id (:id site)}})
        #(merge {:site site} {:posts [%]})))))

(defn login-page [req res]
  (.then
    (db/tx
      #(db/query :sites
         {:limit 1
          :client %
          :where {:slug (.. js/process -env -ZOT_WORLD_SINGLE_TENANT_SLUG)}}))
    (fn [{:keys [title]}]
      (render!
        res
        (components/page
          {:body (dom/section nil
                   (dom/h1 #js {:className "f1 f-6-ns lh-solid measure center tc"} title)
                   (components/login {:csrf-token (.csrfToken req)
                                      :method "post"}))})
        {:title title}))))

(defn login [req res]
  (let [data      (.-body req)
        email     (.-email data)
        original-url (.. req -originalUrl)
        password  (.-password data)
        remember? (= (.-remember data) "on")
        url (when (re-find #"^/[^/]" (.. req -query -url))
              (.. req -query -url))]
    (if (or (nil? email) (nil? password))
      (.redirect res original-url)
      (db/user-by-email-password
        email
        password
        (fn [err user]
          (if (some? user)
            (do
              (gobj/set (.-session req)
                        "userId"
                        (:id user))
              (when remember?
                (gobj/set (.-cookie (.-session req))
                          "maxAge"
                          (* 1000 60 60 24 7)))
              (.redirect res (or url "/")))
            (.redirect res original-url)))))))

(defn logout [req res]
  (if-let [session (.-session req)]
    (.destroy
      session
      #(.redirect res "/"))
    (.redirect res "/")))

(defn register-page [req res]
  (.then
    (db/tx
      #(db/query :sites
         {:limit 1
          :client %
          :where {:slug (.. js/process -env -ZOT_WORLD_SINGLE_TENANT_SLUG)}}))
    (fn [{:keys [title]}]
      (render!
        res
        (components/page
          {:body (dom/section nil
                   (dom/h1 #js {:className "f1 f-6-ns lh-solid measure center tc"} title)
                   (components/register {:action "/register"
                                         :csrf-token (.csrfToken req)
                                         :method "post"}))})
        {:title title}))))

(defn register [req res]
  (let [data      (.-body req)
        username (.-username data)
        phone-number (.-phone_number data)
        email     (.-email data)
        password  (.-password data)
        beta-key (.-beta_key data)]
    (if (or (nil? username)
            (nil? phone-number)
            (nil? email)
            (nil? password)
            (nil? beta-key))
      (.redirect res "/register")
      (.verify jwt
        beta-key
        (.. js/process -env -BETA_KEY_SECRET)
        (clj->js {:algorithms ["HS256"]})
        (fn [err token]
          (if (some? err)
            (.redirect res "/register")
            (.hash bcrypt
              password
              10
              (fn [e hashed-password]
                (if (some? e)
                  (.redirect res "/register")
                  (db/create-user
                    {:username username
                     :email email
                     :password hashed-password
                     :phone_number phone-number}
                    #(if (some? %)
                       (login req res)
                       (.redirect res "/register"))))))))))))

(defn message-receipt []
  (doto (twilio.twiml.MessagingResponse.)
    (.message
      nil
      (rand-nth ["👍" "👌"]))))

(defn create-post [req res]
  (db/create-post
    (.-body req)
    (fn [err post]
      (if (some? err)
        (.sendStatus res 401)
        (do
          (send! res :twiml (message-receipt))
          (enqueue! :post/upload post))))))

(defn get-post-media [req res]
  (-> (s3. #js {:accessKeyId (.. js/process -env -AWS_ACCESS_KEY)
                :secretAccessKey (.. js/process -env -AWS_SECRET_ACCESS_KEY)})
      (.getObject #js {:Bucket (.. js/process -env -AWS_S3_MEDIA_BUCKET)
                       :Key (str (.. req -params -id) "/" (.. req -params -file))})
      .createReadStream
      (.on "error" (fn [err]
                     (condp = (.-code err)
                       "NoSuchKey" (.sendStatus res 404)
                       ; if the server has sent headers we can't respond now
                       (if (.-headersSent res)
                         (pr-str err)
                         (.sendStatus res 500)))))
      (.pipe res)))

(defn theme [req res]
  (send! res :css (styles/css {:pretty-print? (not (production?))})))

(defmulti read om/dispatch)

(defmethod read :posts
  [{:keys [cb user]} key {:keys [site-id until]}]
  (.then
    (db/posts-until
      {:limit *default-post-page-size*
       :until until
       :where {:site_id site-id}})
    (fn [posts]
      (cb (reduce
            #(merge-with (fn [l r]
                           (if (vector? l)
                             (into l r)
                             (merge l r)))
                         %
                         {:posts [`[:posts/by-id ~(:id %2)]]
                          :posts/by-id (assoc {} (:id %2) %2)})
            {}
            posts)))))

(defmulti mutate om/dispatch)

(defmethod mutate 'post/applause
  [{:keys [cb user]} key {:keys [id]}]
  {:action
   (fn []
     (db/toggle-post-engagement
       id
       (:id user)
       (fn [_ post]
         ;; when applause happened, and not by the author
         (when (and
                 (not= (:id user) (:author_id post))
                 (some #(= (:id user) (:id %)) (:likes post)))
           (enqueue!
             :notification/applause
             {:fan user :post post}))
         (cb `{:posts/by-id {~(:id post) ~post}}))))})

(defmethod mutate 'post/conversation
  [{:keys [cb user]} key {:keys [post-id body]}]
  {:action
   (fn []
     (db/add-post-comment
       post-id
       (:id user)
       body
       (fn [_ post]
         (enqueue!
           :notification/comment
           {:author user :body body :post post})
         (cb `{:posts/by-id {~(:id post) ~post}}))))})

(def parser (om/parser {:read read :mutate mutate}))

(defn query [req res]
  (parser
    {:cb #(send! res :edn %)
     :user {:id (.. req -session -userId)}}
    (reader/read-string (-> req
                            .-body
                            .toString))))

(def router
  (doto (.Router express)
    (.get "/" middleware/restrict index)
    (.get "/css/styles.css" theme)
    (.get "/register" (middleware/csrf) register-page)
    (.post "/register" middleware/form-parser middleware/csrf register)
    (.get "/login" (middleware/csrf) login-page)
    (.post "/login" middleware/form-parser (middleware/csrf) login)
    (.get "/logout" logout)
    (.get "/posts/:id" middleware/restrict post-page)
    (.get "/posts/:id/:file" middleware/restrict get-post-media)
    (.post "/query" middleware/restrict middleware/edn-parser query)
    (.post "/twilio" middleware/form-parser (middleware/twilio (production?)) create-post)))
