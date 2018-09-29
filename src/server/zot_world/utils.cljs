(ns ^:figwheel-load zot-world.server.utils
  (:require [goog.object :as gobj]))

(defn get-env
  ([n] (get-env n nil))
  ([n default-value]
   (gobj/get (.. js/process -env) n default-value)))

