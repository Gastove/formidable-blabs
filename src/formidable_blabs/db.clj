(ns formidable-blabs.db
  (:require [camel-snake-kebab
             [core :refer [->kebab-case-keyword ->snake_case_keyword]]
             [extras :refer [transform-keys]]]
            [clj-time.core :as time :refer [now]]
            [clojure.java.jdbc :as jdbc]
            [korma
             [core :as sql :refer [defentity transform prepare select values where]]
             [db :as db :refer [sqlite3 defdb]]]))

(def quotes-table
  [:quotes
   [:user :text]
   [:quote :text]])

(def definitions-table
  [:definitions
   [:id "INTEGER PRIMARY KEY AUTOINCREMENT"]
   [:term :text]
   [:definition :text]
   [:defined_at :text]])

(def tables [quotes-table definitions-table])

(def db-spec (sqlite3 {:classname   "org.sqlite.JDBC"
                       :subprotocol "sqlite"
                       :subname     "formidable_database.db"}))

(defdb main db-spec)

(defn create-table!
  [db-spec schema]
  (let [sql-statement (apply jdbc/create-table-ddl schema)]
    (jdbc/db-do-commands db-spec sql-statement)))

(defn setup-db!
  []
  (doall (map #(create-table! db-spec %) tables)))

(defn- ->kebab-keys
  "converts all keys in a map to kebab-case keywords"
  [fields]
  (transform-keys ->kebab-case-keyword fields))

(defn- ->snake-keys
  "converts all keys in a map to snake-case keywords"
  [fields]
  (transform-keys ->snake_case_keyword fields))

(defn- add-updated
  "adds updated field to a map with given time"
  ([fields] (add-updated fields (now)))
  ([fields n] (assoc fields :defined-at n)))

(defentity definitions
  (prepare add-updated)
  (prepare ->snake-keys)
  (transform ->kebab-keys))

(defn record-definition
  [definition-map]
  (sql/insert definitions (values definition-map)))

(defn find-definiton-by-term
  [t]
  (select definitions (where {:term t})))

(defentity quotes)

(defn record-quote
  [quote-map]
  (sql/insert quotes (values quote-map)))

(defn find-quote-by-user
  [u]
  (select quotes (where {:user u})))

(defn find-quote-by-string
  [s]
  (select quotes (where {:quote [like (str "%" s "%")]})))

(defn find-quote-by-string-and-user
  [u s]
  (select quotes (where {:user u
                         :quote [like (str "%" s "%")]})))
