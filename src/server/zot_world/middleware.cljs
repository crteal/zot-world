(ns ^:figwheel-load zot-world.server.middleware
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [goog.object :as gobj]
            [zot-world.server.db :as db]))

(defonce body-parser (nodejs/require "body-parser"))
(defonce csrf-package (nodejs/require "csurf"))
(defonce twilio-package (nodejs/require "twilio"))

(defn csrf []
  (csrf-package #js {:cookie false}))

(defonce form-parser
  (.urlencoded body-parser #js {:extended false}))

(defonce edn-parser
  (.raw body-parser #js {:type "application/edn"}))

(defn twilio [validate?]
  (.webhook twilio-package #js {:includeHelpers false
                                :validate validate?}))

(defn restrict [req res nxt]
  (if (and (some? (.-session req))
           (some? (.-userId (.-session req))))
    (nxt)
    (.redirect res (str "/login?url=" (.-originalUrl req)))))

(defn load-site! [req res nxt]
  (let [slug (or (.. req -params -slug)
                 (.. js/process -env -ZOT_WORLD_SINGLE_TENANT_SLUG))]
    (.then
      (db/site-with-membership slug (.. req -session -userId))
      (fn [site]
        (gobj/set req "site" site)
        (nxt)))))

(defn forbid [f]
  (fn [req res nxt]
    (if (f req)
      (nxt)
      (.sendStatus res 403))))

(def forbid-site-members
  (forbid
    (fn [req]
      (let [site (.-site req)]
        (and (some? site)
             (or (:is_public site)
                 (:is_member site)
                 (= (.. req -session -userId)
                    (:owner_id site))))))))
