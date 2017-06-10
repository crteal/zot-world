(ns ^:figwheel-always zot-world.client.core
  (:require [cljs.reader :as reader]
            [goog.dom :as gdom]
            [goog.functions]
            [goog.net.XhrIo :as xhr]
            ;; TODO evaluate the need for om-next dependencies
            [om.next :as om]
            [om.dom :as dom]
            [zot-world.components :as components]))

(enable-console-print!)

(def app-state
  (-> js/document
      (.querySelector "script[type='application/edn']")
      .-innerHTML
      reader/read-string))

(defmulti read om/dispatch)

(defmethod read :default
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if-let [[_ value] (find st key)]
      {:value value}
      {:value :not-found})))

(defmethod read :posts
  [{:keys [state ast] :as env} key {:keys [until]}]
  (let [st @state]
    (merge
      {:value (into [] (map #(get-in st %)) (get st key))}
      (when-not (nil? until)
        {:remote ast}))))

(defmulti mutate om/dispatch)

(defmethod mutate :default
  [env key params]
  {:remote true})

(def reconciler
  (om/reconciler
    {:state app-state
     :merge (fn [reconciler state novelty query]
              {:keys (keys novelty)
               :next (merge-with (fn [l r]
                                   (if (vector? l)
                                     (into l r)
                                     (merge l r)))
                                 state
                                 novelty)})
     :send (fn [{:keys [remote]} cb]
             (xhr/send
               "/query"
               (fn [e]
                 (cb (-> e
                         .-currentTarget
                         .getResponseText
                         reader/read-string)))
               "POST"
               (pr-str remote)
               #js {:Accept "application/edn"
                    :Content-Type "application/edn; charset=UTF-8"
                    :X-Requested-With "XMLHttpRequest"}))
     :parser (om/parser {:read read :mutate mutate})}))

(om/add-root! reconciler
  components/Posts (gdom/getElement "app"))

(defn add-listener
  ([event f]
   (add-listener js/window event f))
  ([el event f]
   (doto el
    (.removeEventListener event f)
    (.addEventListener event f))))

(def handle-scroll
  (goog.functions.debounce
    (fn []
      (let [state (om/app-state reconciler)
            post (get-in @state (last (:posts @state)))
            root (om/app-root reconciler)
            params (om/get-params root)
            height (.. js/document -body -clientHeight)
            scroll (- (.. js/document -body -scrollHeight)
                      (.. js/document -body -scrollTop))]
        (when (and (not= (:until params) (:created_at post))
                   (<= (/ (- scroll height) height) 2))
          (om/update-query!
            root
            (fn [q]
              (update q :params merge {:until (:created_at post)
                                       :site-id (get-in @state [:site :id])}))))))
    25))

(add-listener "scroll" #(handle-scroll))
