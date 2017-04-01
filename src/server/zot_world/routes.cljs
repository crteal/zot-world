(ns ^:figwheel-load zot-world.server.routes
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader :as reader]
            [om.next :as om]
            [om.dom :as dom]
            [cljsjs.react.dom.server]
            [clojure.string :as str]
            [zot-world.components :as components]
            [zot-world.server.db :as db]
            [zot-world.styles :as styles]))

(defonce body-parser (nodejs/require "body-parser"))
(defonce csrf (nodejs/require "csurf"))
(defonce express (nodejs/require "express"))
(defonce twilio (nodejs/require "twilio"))

(defn production? []
  (= (.. js/process -env -NODE_ENV)
     "production"))

; middleware
(defonce csrf-middleware (csrf #js {:cookie true}))
(defonce form-parser-middleware (.urlencoded body-parser #js {:extended false}))
(defonce edn-parser-middleware (.raw body-parser #js {:type "application/edn"}))
(defonce twilio-middleware (.webhook twilio #js {:includeHelpers false
                                                 :validate (production?)}))

(defn restrict [req res nxt]
  (if-not (nil? (.-id (.-signedCookies req)))
    (nxt)
    (.redirect res "/login")))

(defn make-type-helper [n content-type f]
  (fn [req res nxt]
    (aset res n (fn [obj]
                  (-> res
                      (.set "Content-Type" content-type)
                      (.send (f obj)))))
    (nxt)))

(def edn (make-type-helper
           "edn"
           "application/edn"
           #(binding [*print-namespace-maps* false]
              (pr-str %))))

(def twiml (make-type-helper
             "twiml"
             "text/xml"
             #(.toString %)))

(def css (make-type-helper
           "css"
           "text/css"
           identity))

; helpers
(defn render! [res component props]
  (.send res (dom/render-to-str (component props))))

; routes
(defn index [req res]
  (db/posts #(render!
               res
               (components/app-page
                 (components/data {:posts %
                                   :user {:id (.-id (.-signedCookies req))}}))
               {:title "zot.world"})))

(defn login-page [req res]
  (render!
    res
    (components/page
      {:body (dom/section nil
               (dom/h1 #js {:className "f1 f-6-ns lh-solid measure center tc"}
                       "zot.world")
               (components/login {:action "/login"
                                  :csrf-token (.csrfToken req)
                                  :method "post"}))})
    {:title "zot.world"}))

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
  (let [data          (.-body req)
        valid-numbers (reduce conj
                              #{}
                              (str/split (.-ZOT_WORLD_USER_NUMBERS (.-env js/process))
                                         ","))]
    (if-not (contains? valid-numbers (.-From data))
      (.sendStatus res 401)
      (db/create-post
        data
        #(.twiml res (message-receipt))))))

(defn api-response-handler [res data component]
  (.format res #js {"application/edn" #(.edn res data)
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
    {:cb #(.edn res %)
     :user {:id (.. req -signedCookies -id)}}
    (reader/read-string (-> req
                            .-body
                            .toString))))

(defn theme [req res]
  (.css res (styles/css {:pretty-print? (not (production?))})))

(def router (-> (.Router express)
                (.get "/" restrict index)
                (.get "/css/styles.css" css theme)
                (.post "/query" restrict edn-parser-middleware edn query)
                (.get "/posts/:id" restrict edn get-post)
                (.get "/login" csrf-middleware login-page)
                (.post "/login" form-parser-middleware csrf-middleware login)
                (.get "/logout" logout)
                (.post "/twilio" form-parser-middleware twilio-middleware twiml create-post)))
