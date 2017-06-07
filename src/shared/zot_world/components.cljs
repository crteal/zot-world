(ns ^:figwheel-load zot-world.components
  (:require [cljsjs.moment]
            [cljsjs.twemoji]
            [clojure.string :as str]
            [goog.functions]
            [markdown.core :as md]
            [markdown.common :as mdc]
            [om.next :as om :refer-macros [defui ui]]
            [om.dom :as dom]))

(defn md->html [html]
  (md/md->html (str/replace html #"[\r\n]+" "<br/>")
               :replacement-transformers [mdc/strong
                                          mdc/bold-italic
                                          mdc/bold
                                          mdc/em
                                          mdc/italics
                                          mdc/strikethrough
                                          mdc/inline-code]))

(defui Login
  Object
  (render [this]
    (let [props (om/props this)
          csrf-token (:csrf-token props)]
      (dom/form (clj->js (merge {:className "center-ns mw6-ns hidden mv4 mh3"}
                                (select-keys props [:action :method])))
        (dom/fieldset #js {:className "ba b--transparent ph0 mh0"}
          (dom/div #js {:className "mt3"}
            (dom/label #js {:className "db fw6 lh-copy f6"} "Email")
            (dom/input #js {:className "pa2 input-reset ba w-100"
                            :name "email"
                            :placeholder "Email address"
                            :required true
                            :type "email"}))
          (dom/div #js {:className "mv3"}
            (dom/label #js {:className "db fw6 lh-copy f6"} "Password")
            (dom/input #js {:className "pa2 input-reset ba w-100"
                            :name "password"
                            :placeholder "Password"
                            :minlength 6
                            :required true
                            :type "password"}))
          (dom/label #js {:className "pa0 ma0 lh-copy f6 pointer"}
            (dom/input #js {:name "remember"
                            :type "checkbox"})
            " Remember me")
          (when-not (nil? csrf-token)
            (dom/input #js {:name "_csrf"
                            :type "hidden"
                            :value csrf-token})))
        (dom/div nil
          (dom/input #js {:className "b ph3 pv2 input-reset ba b--black bg-white grow pointer f6 dib"
                          :type "submit"
                          :value "Sign in"}))))))

(def login (om/factory Login))

(defn emojify [s]
  (.parse js/twemoji s))

(defui Ago
  Object
  (render [this]
    (let [props (om/props this)
          date (-> js/moment (.utc (:date props)))]
      (dom/time #js {:dateTime (-> date .format)
                     :title (-> date (.format "LLL"))}
                (-> date .fromNow)))))

(def ago (om/factory Ago))

(defui Username
  Object
  (render [this]
    (let [{:keys [username] :as props} (om/props this)]
      (dom/strong #js {:dangerouslySetInnerHTML #js {:__html (emojify username)}}))))

(def username (om/factory Username))

(defui Comment
  static om/IQuery
  (query [this]
    '[:id :auther_username :body :likes :created_at])
  Object
  (render [this]
    (let [{:keys [body] :as props} (om/props this)
          created-at (-> js/moment (.utc (:created_at props)))]
      (dom/section #js {:className "pv1"}
        (dom/p #js {:className "f6 f5-ns lh-copy pv1 ma0"
                    :dangerouslySetInnerHTML #js {:__html (-> body
                                                              md->html
                                                              emojify)}})
        (dom/ul #js {:className "list ph0 pv0 ma0 f6"}
          (dom/li #js {:className "dib mr3 gray"}
            (username {:username (:author_username props)})
            " "
            (ago {:date (:created_at props)})))))))

(defn post-comment [c]
  (let [editor (om/react-ref c :editor)
        el (dom/node editor)
        cmt {:body (.-value el)
             :post-id (:id (om/props c))}]
    (when-not (empty? (:body cmt))
      (do
        (set! (.-value el) "")
        (om/transact! c `[(post/conversation ~cmt)])))))

(defui Post
  static om/Ident
  (ident [this {:keys [id]}]
    [:posts/by-id id])
  static om/IQuery
  (query [this]
    '[:id :data :likes :comments :created_at])
  Object
  (render [this]
    (let [{:keys [comments data likes id] :as props} (om/props this)
          created-at (-> js/moment (.utc (:created_at props)))
          ;; TODO push this complexity to the server, normalize posts with types
          num-media (js/parseInt (:NumMedia data))
          state (om/get-state this)]
      (dom/article #js {:className "center-ns mw6-ns hidden mv4 mh3 ba b--near-white"
                        :name "post"}
        (when (> num-media 0)
          (map #(dom/img #js {:className "db mv0"
                              :src (get data (keyword (str "MediaUrl" %)))})
               (range num-media)))
        (when-not (empty? (:Body data))
          (dom/p #js {:className "f6 f5-ns lh-copy ph2 pv3 ma0 bg-white"
                      :dangerouslySetInnerHTML #js {:__html (-> (:Body data)
                                                                md->html
                                                                emojify)}}))
        (dom/ul #js {:className "list ph2 pv3 ma0 f6 bg-near-white"}
          (dom/li #js {:className "grow dib mr3 pointer"
                       :title (when-not (empty? likes)
                                (str "Liked by: " (str/join ", " (map :username likes))))}
            (dom/button #js {:className "pa0 bw0 bg-transparent mid-gray"
                             :dangerouslySetInnerHTML #js {:__html (emojify (str "😍 " (count likes)))}
                             :onClick (goog.functions.debounce
                                        #(om/transact!
                                           this
                                           `[(post/applause ~(select-keys props [:id]))])
                                        250)
                             :type "button"}))
          (dom/li #js {:className "grow dib mr3 pointer"}
            (dom/button #js {:className "pa0 bw0 bg-transparent mid-gray"
                             :dangerouslySetInnerHTML #js {:__html (emojify (str "💬 " (count comments)))}
                             :name "post-conversation"
                             :onClick #(om/set-state! this {:expanded (not (:expanded state))})
                             :type "button"}))
          (dom/li #js {:className "dib mr3"}
            (ago {:date (:created_at props)})))
        (when (:expanded state)
          (dom/section #js {:className "pb3 ph2 bg-near-white"}
            (apply dom/section nil
              (map #((om/factory Comment {:keyfn :id}) %) comments))
            (dom/section #js {:className "pv2"}
              (dom/textarea #js {:autoFocus true
                                 :className "w-100 mb2"
                                 :ref :editor
                                 :style #js {:minHeight "4rem"
                                             :resize "none"}})
              (dom/input #js {:className "b ph3 pv2 input-reset ba b--black bg-white grow pointer f6 dib"
                              :onClick (goog.functions.debounce
                                         #(post-comment this)
                                         250)
                              :type "submit"
                              :value "Post a comment"}))))))))

(def post (om/factory Post {:keyfn :id}))

(defui Posts
  static om/IQueryParams
  (params [this]
    {:until nil})
  static om/IQuery
  (query [_]
    `[({:posts ~(om/get-query Post)} ~'{:until ?until})])
  Object
  (render [this]
    (let [{:keys [posts]} (om/props this)]
      (apply dom/section #js {:name "posts"}
             (map post posts)))))

(def posts (om/factory Posts))

(defui Data
  Object
  (render [this]

    (dom/script #js {:type "application/edn"
                     :dangerouslySetInnerHTML #js {:__html (pr-str (om/props this))}})))

(def data (om/factory Data))

(defn page [{:keys [head body scripts]}]
  (om/factory
    (ui
      Object
      (render [this]
        (let [{:keys [title] :as props} (om/props this)]
          (dom/html #js {:lang "en"}
            (dom/head nil
              (dom/meta #js {:charSet "utf-8"})
              (dom/meta #js {:name "viewport"
                             :content "width=device-width, initial-scale=1"})
              (dom/meta #js {:name "theme-color"
                             :content "#ffffff"})
              (dom/title nil title)
              (dom/link #js {:href "/apple-touch-icon.png"
                             :rel "apple-touch-icon"
                             :sizes "180x180"})
              (dom/link #js {:href "/favicon-32x32.png"
                             :rel "icon"
                             :sizes "32x32"
                             :type "image/png"})
              (dom/link #js {:href "/favicon-16x16.png"
                             :rel "icon"
                             :sizes "16x16"
                             :type "image/png"})
              (dom/link #js {:href "/manifest.json"
                             :rel "manifest"})
              (dom/link #js {:href "/css/tachyons.min.css"
                             :rel "stylesheet"})
              head)
            (dom/body #js {:className "mid-gray"}
              body
              scripts)))))))

(defn app-page [c]
  (page {:head (dom/link #js {:href "/css/styles.css"
                              :rel "stylesheet"})
         :body (dom/section nil
                 (dom/header #js {:className "bg-white fixed w-100 ph3 pv3"
                                  :style #js {:zIndex 1}}
                   (dom/h1 #js {:className "f1 f-4-ns lh-solid center tc mv0"}
                           "zot.world"))
                 (dom/section #js {:className "pt5"
                                   :id "app"})
                 c)
         :scripts (dom/script #js {:src "/js/main.js"})}))
