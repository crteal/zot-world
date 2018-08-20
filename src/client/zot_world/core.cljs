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
      {:value (reduce
                (fn [memo query]
                  (if-let [post (get-in st query)]
                    (conj memo post)
                    memo))
                []
                (get st key))}
      (when-not (nil? until)
        {:remote ast}))))

(defmulti mutate om/dispatch)

(defmethod mutate :default
  [env key params]
  {:remote true})

(defmethod mutate 'post/delete
  [{:keys [state]} _ {:keys [id]}]
  {:remote true
   :action
   (fn []
     (swap! state update-in [:posts/by-id] dissoc id))})

(def reconciler
  (om/reconciler
    {:state app-state
     :shared (select-keys app-state [:user])
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
