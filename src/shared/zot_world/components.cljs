(ns ^:figwheel-load zot-world.components
  (:require [cljsjs.moment]
            [cljsjs.twemoji]
            [clojure.string :as str]
            [hom.core :refer-macros [component]]
            [goog.events :as gevents]
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

(defmulti make-field
  (fn [config]
    (:type config)))

(defmethod make-field "checkbox" [config]
  (dom/div #js {:className "mt3"}
    (dom/label #js {:className "pa0 ma0 lh-copy f6 pointer"}
      (dom/input (clj->js (dissoc config :label)))
      (str " " (get config :label
                    (str/capitalize (:name config)))))))

(defmethod make-field "hidden" [config]
  (dom/input (clj->js config)))

(defmethod make-field "submit" [config]
  (dom/input
    (clj->js
      (merge config
             {:className "b ph3 pv2 input-reset ba b--black bg-white grow pointer f6 dib"}))))

(defmethod make-field :default [config]
  (dom/div #js {:className "mt3"}
    (dom/label #js {:className "db fw6 lh-copy f6"}
      (get config :label
           (str/capitalize (:name config))))
    (dom/input (clj->js (merge
                          (dissoc config :label)
                          {:className "pa2 input-reset ba w-100"})))))

(defn ^:private make-fieldset [config]
  (dom/fieldset #js {:className "ba b--transparent ph0 mh0"}
    (map
      #(make-field
         (merge
           {:name (name %)}
           (get config %)))
      (keys config))))

(defui Login
  Object
  (render [this]
    (let [props (om/props this)]
      (dom/form (clj->js (merge {:className "center-ns mw6-ns hidden mv4 mh3"}
                                (select-keys props [:action :method])))
        (make-fieldset
          (merge
            {:email {:placeholder "Email address"
                     :required true
                     :type "email"}
             :password {:minLength 6
                        :placeholder "Password"
                        :required true
                        :type "password"}
             :remember {:label "Remember me"
                        :type "checkbox"}}
            (when-some [csrf-token (:csrf-token props)]
              {:_csrf {:type "hidden"
                       :value csrf-token}})))
        (make-field {:type "submit"
                     :value "Sign in"})))))

(def login (om/factory Login))

(defui Register
  Object
  (render [this]
    (let [props (om/props this)]
      (dom/form (clj->js (merge {:className "center-ns mw6-ns hidden mv4 mh3"}
                                (select-keys props [:action :method])))
        (make-fieldset
          (merge
            {:username {:placeholder "Username"
                        :required true}
             :phone_number {:label "Phone Number"
                            :placeholder "Phone number"
                            :required true
                            :type "tel"}
             :email {:placeholder "Email address"
                     :required true
                     :type "email"}
             :password {:minlength 6
                        :placeholder "Password"
                        :required true
                        :type "password"}
             :beta_key {:label "Beta Key"
                        :placeholder "Beta key"
                        :required true
                        :type "text"}
             :site_id {:type "hidden"
                       :value (:site-id props)}}
            (when-some [csrf-token (:csrf-token props)]
              {:_csrf {:type "hidden"
                       :value csrf-token}})))
        (make-field {:type "submit"
                     :value "Sign up"})))))

(def register (om/factory Register))

(defn emojify [s]
  (.parse js/twemoji s))

(defui Ago
  Object
  (render [this]
    (let [props (om/props this)
          date (-> js/moment (.utc (:date props)))]
      (component
        [:time {:dateTime (-> date .format)
                :title (-> date (.format "LLL"))}
         (-> date .fromNow)]))))

(def ago (om/factory Ago))

(defui Username
  Object
  (render [this]
    (let [{:keys [username] :as props} (om/props this)]
      (component
        [:strong {:dangerouslySetInnerHTML {:__html (emojify username)}}]))))

(def username (om/factory Username))

(defui Comment
  static om/IQuery
  (query [this]
    '[:id :auther_username :body :likes :created_at])
  Object
  (render [this]
    (let [{:keys [body] :as props} (om/props this)]
      (component
        [:section.pv1
         [:p.f6.f5-ns.lh-copy.pv1.ma0
          {:dangerouslySetInnerHTML {:__html (-> body md->html emojify)}}]
         [:ul.list.ph0.pv0.ma0.f6
          (dom/li #js {:className "dib mr3 gray"}
            (username {:username (:author_username props)})
            " "
            (ago {:date (:created_at props)}))]]))))

(defn post-comment [c]
  ; TODO react 16 breaks string refs, this is an Om bug
  (let [c-el (dom/node c)
        ; editor (om/react-ref c :editor)
        ; el (dom/node editor)
        el (.querySelector c-el "textarea")
        cmt {:body (.-value el)
             :post-id (:id (om/props c))}]
    (when-not (empty? (:body cmt))
      (do
        (set! (.-value el) "")
        (om/transact! c `[(post/conversation ~cmt)])))))

(defmulti make-media
  (fn [{:keys [content-type]}]
    content-type))

(defn make-video [{:keys [id content-type post-id url]}]
  (component
    [:video.db.mv0.w-100 {:autoPlay true
                          :controls true
                          :loop true
                          :muted true
                          :key id}
     [:source {:src (str "/posts/" post-id "/" id ".webm")
               :type "video/webm"}]
     [:source {:src url
               :type content-type}]]))

(defmethod make-media "video/3gpp" [options]
  (make-video options))

(defmethod make-media "video/mp4" [options]
  (make-video options))

(defmethod make-media "video/mpeg" [options]
  (make-video options))

(defmethod make-media :default [{:keys [id post-id url]}]
  (let [file-path (str "/posts/" post-id "/" id)]
    (dom/picture #js {:key id}
      (dom/source #js {:srcset (str file-path ".webp") :type "image/webp"})
      (dom/img #js {:className "db mv0 w-100"
                    :src url
                    :loading "lazy"
                    :decoding "async"}))))

(defui Media
  Object
  (render [this]
    (let [{:keys [data id] :as props} (om/props this)
          ;; TODO push this complexity to the server, normalize posts with types
          num-media (js/parseInt (get data :NumMedia))]
      (component
        [:section {:name "post-media"}
         (map #(make-media
                {:id %
                 :post-id id
                 :component this
                 :content-type (get data (keyword (str "MediaContentType" %)))
                 :url (get data (keyword (str "MediaUrl" %)))})
              (range num-media))]))))

(def media (om/factory Media))

(defn likes-emoji [likes {:keys [id] :as user}]
  (if (empty? (filter #(= id (:id %)) likes))
    "🙂"
    "😍"))

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
          state (om/get-state this)
          {:keys [user] :as shared} (om/shared this)]
      (dom/article #js {:className "center-ns mw6-ns hidden mv4 mh3 ba b--near-white"
                        :name "post"}
        (media props)
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
                             :dangerouslySetInnerHTML #js {:__html (emojify (str (likes-emoji likes user) " " (count likes)))}
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
          (dom/li #js {:className "dib mr3 gray"}
            "posted by "
            (username {:username (:author_username props)})
            " "
            (ago {:date (:created_at props)})))
        (when (:expanded state)
          (dom/section #js {:className "pb3 ph2 bg-near-white"}
            (apply dom/section nil
              (map #((om/factory Comment {:keyfn :id}) %) comments))
            (dom/section #js {:className "pv2"}
              (dom/textarea #js {:autoFocus true
                                 :className "w-100 mb2"
                                 ; :ref :editor
                                 :style #js {:minHeight "4rem"
                                             :resize "none"}})
              (dom/input #js {:className "b ph3 pv2 input-reset ba b--black bg-white grow pointer f6 dib"
                              :onClick (goog.functions.debounce
                                         #(post-comment this)
                                         250)
                              :type "submit"
                              :value "Post a comment"}))))))))

(def post (om/factory Post {:keyfn :id}))

(def make-posts-scroll-handler
  (memoize
    (fn [component]
      (goog.functions.debounce
        (fn []
          (let [{:keys [posts]} (om/props component)
                post (last posts)
                params (om/get-params component)
                height (.. js/document -body -clientHeight)
                scroll (- (.. js/document -body -scrollHeight)
                          (.. js/document -body -scrollTop))]
            (when (and (not= (:until params) (:created_at post))
                       (<= (/ (- scroll height) height) 2))
              (om/set-query! component
                             {:params {:site-id (:site_id post)
                                       :until (:created_at post)}}))))
        25))))

(defn calendar-month-day [events year month day]
  (dom/div #js {:className "tc pa2"}
    (when-not (string? day)
      (if (contains? events (inc day))
        (dom/a #js {:className "link dim"
                    :href (str "/?year=" year "&month=" month "&day=" (inc day))}
          (inc day))
        (inc day)))))

(defui CalendarMonth
  Object
  (render [this]
    (let [{:keys [events month year locale] :or {locale "en"}} (om/props this)
          date (js/moment.utc (js/Date. year month))]
      (component
        [:section.calendar-month.center-ns.mw6-ns.hidden.mv4.mh3.ba.b--near-white
         [:h2.tc
          [:a.link.dim {:href (str "/?year=" year "&month=" month)} (.format date "MMMM")]]
         [:section.calendar.mb3
          (concat (map #(component [:div.b.tc.pa2 %])
                       (-> (js/moment)
                           (.locale locale)
                           .localeData
                           .weekdaysMin))
                  (map (partial calendar-month-day events year month)
                       (concat (repeat (.day date) "")
                               (range (.daysInMonth date)))))]]))))

(def calendar-month (om/factory CalendarMonth))

(defui CalendarYear
  Object
  (render [this]
    (let [{:keys [events year] :as props} (om/props this)
          date (js/moment.utc)
          start-month (or (apply min (keys events)) 0)
          end-month (if (= year (.year date))
                      (inc (.month date))
                      12)]
      (component
        [:section.calendar-year
         [:h2.tc year]
         [:section.calendar-months
          (map #(calendar-month (merge props {:events (get events %)
                                              :month %}))
               (reverse (range start-month end-month)))]]))))

(def calendar-year (om/factory CalendarYear))

(defui CalendarListItem
  Object
  (render [this]
    (let [{:keys [year]} (om/props this)]
      (component
        [:li.lh-copy
         [:a.link.dim {:href (str "calendar/" year)} year]]))))

(def calendar-list-item (om/factory CalendarListItem))

(defui CalendarList
  Object
  (render [this]
    (let [{:keys [years]} (om/props this)]
      (component
        [:ul.list.center-ns.mw6-ns.hidden.mv4.mh3
         (map calendar-list-item years)]))))

(def calendar-list (om/factory CalendarList))

(defui Posts
  static om/IQueryParams
  (params [this]
    {:site-id nil
     :until nil})
  static om/IQuery
  (query [_]
    `[({:posts ~(om/get-query Post)} ~'{:site-id ?site-id :until ?until}) :post-page-size])
  Object
  (componentDidMount [this]
    (let [{:keys [posts post-page-size]} (om/props this)]
      (when (>= (count posts) post-page-size)
        (gevents/listen
          js/window goog.events.EventType.SCROLL
          (make-posts-scroll-handler this)))))
  (componentWillUnmount [this]
    (let [{:keys [posts post-page-size]} (om/props this)]
      (when (>= (count posts) post-page-size)
        (gevents/unlisten
          js/window goog.events.EventType.SCROLL
          (make-posts-scroll-handler this)))))
  (render [this]
    (let [{:keys [posts]} (om/props this)]
      (component
        [:section {:name "posts"} (map post posts)]))))

(def posts (om/factory Posts))

(defui Data
  Object
  (render [this]
    (let [edn (pr-str (om/props this))]
      (component
        [:script {:type "application/edn"
                  :dangerouslySetInnerHTML {:__html edn}}]))))

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
              (dom/link #js {:href "/favicon-32x32.png"
                             :rel "icon"
                             :sizes "32x32"
                             :type "image/png"})
              (dom/link #js {:href "/manifest.json"
                             :rel "manifest"})
              (dom/script #js {:src "https://cdn.jsdelivr.net/npm/pwacompat"
                               :crossOrigin "anonymous"})
              (dom/link #js {:href "/css/tachyons.min.css"
                             :rel "stylesheet"})
              head)
            (dom/body #js {:className "mid-gray system-sans-serif"}
              body
              scripts)))))))

(defn app-page-layout
  ([title c] (app-page-layout title c nil))
  ([title c scripts]
   (page {:head (dom/link #js {:href "/css/styles.css"
                               :rel "stylesheet"})
          :body (dom/section nil
                  (dom/header #js {:className "bg-white fixed w-100 ph3 pv3"
                                   :style #js {:zIndex 1}}
                  (dom/h1 #js {:className "f1 f-4-ns lh-solid center tc mv0"}
                    (dom/a #js {:className "link dim mid-gray"
                                :href "/"}
                      title)))
                  (dom/section #js {:className "pt5"
                                    :id "app"})
                  c)
         :scripts scripts})))

(defn app-page [title c]
  (app-page-layout title c (dom/script #js {:src "/js/main.js"})))
