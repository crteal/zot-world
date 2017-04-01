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

(extend-type js/NodeList
  ISeqable
  (-seq [array] (array-seq array 0)))

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
  [{:keys [state query] :as env} key {:keys [start end]}]
  (let [st @state]
    {:value (apply subvec
             (into [] (map #(get-in st %)) (get st key))
             (filter some? [start end]))}))

(defmulti mutate om/dispatch)

(defmethod mutate :default
  [env key params]
  {:remote true})

(def reconciler
  (om/reconciler
    {:state app-state
     :merge (fn [reconciler state novelty query]
              {:keys (keys novelty)
               :next (merge-with merge state novelty)})
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
            root (om/app-root reconciler)
            item-count (count (:posts @state))
            params (om/get-params root)
            height (.. js/document -body -clientHeight)
            scroll (- (.. js/document -body -scrollHeight)
                      (.. js/document -body -scrollTop))]
        (when (and (< (:end params) item-count)
                   (<= (- scroll height) 300))
          (om/update-query!
            root
            (fn [q]
              (update q :params conj {:end (js/Math.min
                                             item-count
                                             (+ (:end params) 5))}))))))
    50))

(defn on-scroll [e]
  (handle-scroll))

(add-listener "scroll" on-scroll)
