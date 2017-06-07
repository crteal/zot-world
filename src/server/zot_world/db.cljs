(ns ^:figwheel-load zot-world.server.db
  (:require [cljs.nodejs :as nodejs]
            [cljsjs.moment]))

(defonce bcrypt (nodejs/require "bcrypt"))
(defonce sql (.configure (nodejs/require "pg-bricks")
                         (.-DATABASE_URL (.-env js/process))))

(defn to-json [obj]
  (.parse js/JSON (.stringify js/JSON obj)))

(defn str-sql [line & lines]
  (reduce #(str % "\n" %2) line lines))

(defn row-handler [cb]
  (fn [error rows]
    (if-not (nil? error)
      (throw error)
      ; TODO eliminate requirement to serialize objects to strip foreign
      ; constructors
      (cb (js->clj (to-json rows) :keywordize-keys true)))))

(defn posts-query
  ([f] (posts-query f))
  ([client f]
   (-> client
       .select
       (.from "posts_view")
       f)))

(defn recent-posts
  ([cb] (recent-posts sql cb))
  ([client cb]
   (posts-query
     client
     #(-> %
          (.limit 5)
          (.rows (row-handler cb))))))

(defn posts-until
  ([until cb] (posts-until sql until cb))
  ([client until cb]
   (posts-query
     client
     #(-> %
          (.where (-> sql
                      .-sql
                      (.lt "created_at" until)))
          (.limit 5)
          (.rows (row-handler cb))))))

(defn post
  ([query cb] (post sql query cb))
  ([client query cb]
   (-> client
       .select
       (.from "posts_view")
       (.where (clj->js query))
       (.rows (row-handler #(cb (first %)))))))

(defn now-timestamp []
  (-> js/moment
      .utc
      .toDate
      .toISOString))

(defn create-post [data cb]
  (-> sql
      (.insert "posts" #js {:data data
                            :created_at (now-timestamp)})
          (.returning "*")
          (.row (row-handler cb))))

(defn create-comment
  ([data cb]
   (create-comment sql data cb))
  ([client data cb]
   (-> client
       (.insert "comments" (clj->js (conj data
                                          {:created_at (now-timestamp)})))
       (.returning "*")
       (.row (row-handler cb)))))

(defn users [query cb]
  (-> sql
      .select
      (.from "users")
      (.where (clj->js query))
      (.rows (row-handler cb))))

(defn user [query cb]
  (users
    query
    #(cb (first %))))

(defn user-by-email [email cb]
  (user {:email email
         :is_deleted false}
        cb))

(defn user-by-email-password [email password cb]
  (user-by-email
    email
    (fn [user]
      (if-not (nil? user)
        (.compare bcrypt password
                         (:password user)
                         (fn [err, match?]
                           (if-not (nil? err)
                             (throw err)
                             (cb (when match?
                                   user)))))
        (cb)))))

(defn update-post-likes
  ([post-id user-id cb]
   (update-post-likes sql post-id user-id cb))
  ([client post-id user-id cb]
   (-> client
       (.raw (str-sql
               "UPDATE"
                  "posts"
               "SET likes = CASE WHEN $2 = ANY(likes) THEN array_subtract(likes, array[$2]::uuid[])"
                                "ELSE array_append(likes, $2::uuid)"
                           "END"
               "WHERE id = $1;")
             #js [post-id user-id])
       (.run cb))))

(defn toggle-post-engagement [post-id user-id cb]
  (.transaction sql
    (fn [client callback]
      (update-post-likes
        client
        post-id
        user-id
        (fn []
          (post
            client
            {:id post-id}
            #(callback nil %)))))
    cb))

(defn add-post-comment [post-id user-id body cb]
  (.transaction sql
    (fn [client callback]
      (create-comment
        client
        {:author_id user-id
         :post_id post-id
         :body body}
        (fn [_]
          (post
            client
            {:id post-id}
            #(callback nil %)))))
    cb))
