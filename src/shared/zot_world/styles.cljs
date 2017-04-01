(ns ^:figwheel-load zot-world.styles
  (:require [garden.core :as garden]))

(def twemoji [:img.emoji {:height "1em"
                          :width "1em"
                          :margin "0 .05em 0 .1em"
                          :vertical-align "-0.1em"}])

(def theme [twemoji])

(defn css
  ([] (css nil))
  ([flags]
   (garden/css flags theme)))
