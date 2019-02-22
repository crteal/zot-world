(ns ^:figwheel-always zot-world.client.core
  (:require [cljs.reader :as reader]
            [goog.dom :as gdom]
            [goog.functions]
            [goog.net.XhrIo :as xhr]
            [goog.object :as gobj]
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

(defn visible? [el]
  (let [rect (.getBoundingClientRect el)]
    (and
      (> (gobj/get rect "bottom") 0)
      (> (gobj/get rect "right") 0)
      (< (gobj/get rect "left")
          (gobj/get js/window "innerWidth"))
      (< (gobj/get rect "top")
          (gobj/get js/window "innerHeight")))))

(defn playing? [el]
  (and
    (> (gobj/get el "currentTime") 0)
    (false? (gobj/get el "paused"))
    (false? (gobj/get el "ended"))
    (> (gobj/get el "readyState") 2)))

(.addEventListener js/window "scroll"
  #(doseq [video (.querySelectorAll js/document "video")]
     (when (and (playing? video)
                (not (visible? video)))
       (.pause video))))

(om/add-root! reconciler
  components/Posts (gdom/getElement "app"))
