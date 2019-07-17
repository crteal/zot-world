(ns ^:figwheel-load zot-world.server.email
  (:refer-clojure :exclude [send])
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]))

(defonce ses (nodejs/require "aws-sdk/clients/ses"))

(defn send [{:keys [from html subject text to]
             :or {from (.. js/process -env -SYSTEM_EMAIL_ADDRESS)}
             :as options}]
  (println options)
  (js/Promise.
    (fn [res rej]
      (-> (ses. #js {:accessKeyId (.. js/process -env -AWS_ACCESS_KEY)
                     :secretAccessKey (.. js/process -env -AWS_SECRET_ACCESS_KEY)
                     :region (.. js/process -env -AWS_REGION)})
          (.sendEmail
            (clj->js
              {:ReturnPath from
               :Source from
               :Destination
               {:ToAddresses
                (if-some [override (.. js/process -env -SYSTEM_EMAIL_ADDRESS_RECIPIENT_OVERRIDE)]
                  [override]
                  to)}
               :Message
               {:Subject {:Charset "UTF-8"
                          :Data subject}
                :Body (merge
                        {}
                        (when-not (str/blank? text)
                          {:Text
                           {:Charset "UTF-8"
                            :Data text}})
                        (when-not (str/blank? html)
                          {:Html
                           {:Charset "UTF-8"
                            :Data html}}))}})
            (fn [err data]
              (if (some? err)
                (rej err)
                (res data))))))))
