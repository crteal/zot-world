(ns ^:figwheel-load zot-world.server.routes
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader :as reader]
            [om.next :as om]
            [om.dom :as dom]
            [cljsjs.react.dom.server]
            [clojure.string :as str]
            [zot-world.components :as components]
            [zot-world.server.db :as db]
            [zot-world.server.middleware :as middleware]
            [zot-world.styles :as styles]
            [zot-world.server.queue :as queue]))

(defonce express (nodejs/require "express"))
(defonce twilio (nodejs/require "twilio"))

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
          serializer (if-some [f (:serializer config)]
                       f
                       identity)]
      (-> res
          (cond->
            (contains? config :headers) (.set (clj->js (:headers config))))
          (.send (serializer data))))))

(defonce send-response (make-send serializers))

; helpers
(defn render! [res component props]
  (.send res (dom/render-to-str (component props))))

; routes
(defn index [req res]
  (db/tx
    (fn [client cb]
      (db/site
        client
        {:slug (.. js/process -env -ZOT_WORLD_SINGLE_TENANT_SLUG)}
        (fn [site]
          (db/recent-posts
            client
            #(cb nil (merge {:site site} {:posts %}))))))
    (fn [err {:keys [site posts]}]
      (render!
        res
        (components/app-page
          (components/data {:posts posts
                            :site (select-keys site [:id])
                            :user {:id (.-id (.-signedCookies req))}}))
        {:title (:title site)}))))

(defn login-page [req res]
  (db/tx
    (fn [client cb]
      (db/site
        client
        {:slug (.. js/process -env -ZOT_WORLD_SINGLE_TENANT_SLUG)}
        #(cb nil %)))
    (fn [err {:keys [title]}]
      (render!
        res
        (components/page
          {:body (dom/section nil
                   (dom/h1 #js {:className "f1 f-6-ns lh-solid measure center tc"}
                     title)
                   (components/login {:action "/login"
                                      :csrf-token (.csrfToken req)
                                      :method "post"}))})
        {:title title}))))

(defn login [req res]
  (let [data      (.-body req)
        email     (.-email data)
        password  (.-password data)
        remember? (= (.-remember data) "on")]
    (if (or (nil? email) (nil? password))
      (.redirect res "/login")
      (db/user-by-email-password
        email
        password
        (fn [user]
          (if-not (nil? user)
            (-> res
              (.cookie "id"
                       (:id user)
                       #js {:maxAge (if remember?
                                      (* 1000 60 60 24 7)
                                      3600000)
                            :httpOnly true
                            :signed true})
              (.redirect "/"))
            (.redirect res "/login")))))))

(defn logout [req res]
  (-> res
      (.clearCookie "id")
      (.redirect "/")))

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
          (send-response res :twiml (message-receipt))
          (queue/enqueue
            {:data post
             :queue (.. js/process -env -POST_QUEUE_NAME)
             :connection-string (.. js/process -env -RABBITMQ_BIGWIG_TX_URL)}))))))

(defn api-response-handler [res data component]
  (.format res #js {"application/edn" #(send-response res :edn data)
                    :json #(.send res (clj->js data))
                    :html #(render! res component data)}))

(defn get-post [req res]
  (db/post
    {:id (.. req -params -id)}
    #(api-response-handler
       res
       %
       components/post)))

(defmulti read om/dispatch)

(defmethod read :posts
  [{:keys [cb user]} key {:keys [until]}]
  (db/posts-until
    until
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
         (cb `{:posts/by-id {~(:id post) ~post}}))))})

(def parser (om/parser {:read read :mutate mutate}))

(defn query [req res]
  (parser
    {:cb #(send-response res :edn %)
     :user {:id (.. req -signedCookies -id)}}
    (reader/read-string (-> req
                            .-body
                            .toString))))

(defn theme [req res]
  (send-response res :css (styles/css {:pretty-print? (not (production?))})))

(def router (-> (.Router express)
                (.get "/" middleware/restrict index)
                (.get "/css/styles.css" theme)
                (.post "/query" middleware/restrict middleware/edn-parser query)
                (.get "/posts/:id" middleware/restrict get-post)
                (.get "/login" middleware/csrf login-page)
                (.post "/login" middleware/form-parser middleware/csrf login)
                (.get "/logout" logout)
                (.post "/twilio" middleware/form-parser (middleware/twilio (production?)) create-post)))
