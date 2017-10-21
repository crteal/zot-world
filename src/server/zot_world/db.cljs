(ns ^:figwheel-load zot-world.server.db
  (:require [cljs.nodejs :as nodejs]
            [cljsjs.moment]))

(defonce bcrypt (nodejs/require "bcrypt"))
(defonce sql (.configure (nodejs/require "pg-bricks")
                         (.. js/process -env -DATABASE_URL)))

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

(defn wrap-promise [f cb]
  (-> (f)
      (.then #(cb nil %))
      (.catch #(cb %))))

(defn tx
  ([f]
   (js/Promise.
     (fn [res rej]
       (tx (fn [client cb]
             (wrap-promise #(f client) cb))
           (fn [err data]
             (if (some? err)
               (rej err)
               (res data)))))))
  ([f cb]
   (.transaction sql f cb)))

(def table-query-configurations {:posts {:table "posts_view"}
                                 :sites {:table "sites_view"}
                                 :users {:table "users"}})

(defn make-query-fn [query-configurations]
  (fn [query-type {:keys [client limit where] :as config}]
    (js/Promise.
      (fn [res rej]
        (let [query-type-config (get query-configurations query-type)
              handler (fn [err data]
                        (if (some? err)
                          (rej err)
                          (res (js->clj (to-json data) :keywordize-keys true))))]
          (-> (if (some? client)
                client
                sql)
              .select
              (.from (:table query-type-config))
              (cond->
                (some? where) (.where (clj->js where)))
              (cond->
                (some? limit) (.limit limit))
              (cond->
                (= limit 1) (.row handler)
                (or (nil? limit) (> limit 1)) (.rows handler))))))))

(def query (make-query-fn table-query-configurations))

(defn posts-until [{:keys [until] :as config}]
  (query
    :posts
    (merge
      config
      {:where (-> sql
                  .-sql
                  (.and (clj->js (:where config))
                        (-> sql
                            .-sql
                            (.lt "created_at" until))))})))

(defn now-timestamp []
  (-> js/moment
      .utc
      .toDate
      .toISOString))

(defn site-with-membership
  ([slug user-id] (site-with-membership nil slug user-id))
  ([client slug user-id]
   (println slug user-id)
    (js/Promise.
      (fn [res rej]
        (-> (or client sql)
            (.raw (str-sql
                       "SELECT"
                         "sv.*,"
                         "("
                           "SELECT"
                             "id"
                           "FROM members_view"
                           "WHERE site_id = sv.id"
                           "AND user_id = $2"
                         ") IS NOT NULL AS is_member"
                       "FROM sites_view sv"
                       "WHERE sv.slug = $1"
                       "LIMIT 1;")
                  #js [slug user-id])
            (.row
              (fn [err row]
                (if (some? err)
                  (rej err)
                  (res (js->clj (to-json row) :keywordize-keys true))))))))))

(defn create-post [data cb]
  (tx
    (fn [client cb]
      (-> client
          (.raw (str-sql
                  "SELECT"
                    "mv.user_id,"
                    "mv.user_phone_number,"
                    "mv.site_id"
                  "FROM members_view mv"
                  "JOIN sites_view sv"
                  "ON mv.site_id = sv.id"
                  "AND sv.phone_number = $1"
                  "WHERE mv.permission IN ('admin', 'write')"
                  "AND mv.user_phone_number = $2"
                  "UNION"
                  "SELECT"
                    "sv.owner_id AS user_id,"
                    "sv.owner_phone_number AS user_phone_number,"
                    "sv.id AS site_id"
                  "FROM sites_view sv"
                  "WHERE sv.phone_number = $1"
                  "AND sv.owner_phone_number = $2;")
                #js [(.-To data) (.-From data)])
          (.rows (fn [err [author]]
                   (if (or (some? err)
                           (nil? author))
                     (cb (if (some? err)
                           err
                           "insufficient privileges"))
                     (-> client
                        (.insert "posts" #js {:data data
                                              :author_id (.-user_id author)
                                              :site_id (.-site_id author)
                                              :created_at (now-timestamp)})
                        (.returning "*")
                        (.row (row-handler #(cb nil %)))))))))
    cb))

(defn create-comment
  ([data cb]
   (create-comment sql data cb))
  ([client data cb]
   (-> client
       (.insert "comments" (clj->js (conj data
                                          {:created_at (now-timestamp)})))
       (.returning "*")
       (.row (row-handler cb)))))

(defn users-in
  ([ids] (users-in nil ids))
  ([client ids]
   (query
     :users
     {:client client
      :where (-> sql
                 .-sql
                 (.in "id" (clj->js ids)))})))

(defn user-by-email [email]
  (query
    :users
    {:limit 1
     :where {:email email
             :is_deleted false}}))

(defn user-by-email-password [email password cb]
  (-> (user-by-email email)
      (.then (fn [user]
               (if (some? user)
                 (.compare bcrypt password
                           (:password user)
                           (fn [err, match?]
                             (if (some? err)
                               (cb err)
                               (cb nil (when match? user)))))
                 (cb nil))))
      (.catch #(cb %))))

(defn create-user
  ([data cb]
   (create-user sql data cb))
  ([client data cb]
   (-> client
       (.insert "users" (clj->js (conj data
                                       {:created_at (now-timestamp)})))
       (.returning "*")
       (.row (row-handler cb)))))

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
                           "END,"
               "updated_at = $3"
               "WHERE id = $1;")
             #js [post-id user-id (now-timestamp)])
       (.run cb))))

(defn toggle-post-engagement [post-id user-id cb]
  (tx (fn [client callback]
        (update-post-likes
          client
          post-id
          user-id
          (fn []
            (wrap-promise #(query
                             :posts
                             {:limit 1
                              :client client
                              :where {:id post-id}})
                          callback))))
      cb))

(defn add-post-comment [post-id user-id body cb]
  (tx (fn [client callback]
        (create-comment
          client
          {:author_id user-id
           :post_id post-id
           :body body}
          (fn []
            (wrap-promise #(query
                             :posts
                             {:limit 1
                              :client client
                              :where {:id post-id}})
                          callback))))
      cb))
