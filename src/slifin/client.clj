(ns slifin.client
  (:require [com.rpl.rama :as r :refer :all]))

(def MODULE "slifin.card-game/CardGameModule")

(defn cmgr [] (open-cluster-manager))

(defn depot [mgr dep-name]
  (foreign-depot mgr MODULE dep-name))

(defn qtop [mgr qname]
  (foreign-query mgr MODULE qname))

(defn create-player! [mgr {:keys [player-id name ts]}]
  (foreign-append! (depot mgr "*player-cmds")
                   {:op :create-player :player-id player-id :name name :ts ts}))

(defn create-deck! [mgr {:keys [player-id deck-id name ts]}]
  (foreign-append! (depot mgr "*deck-cmds")
                   {:op :create-deck :player-id player-id :deck-id deck-id :name name :ts ts}))

(defn get-player [mgr player-id]
  (first (foreign-invoke-query (qtop mgr "get-player") player-id)))

(defn list-deck-ids [mgr player-id]
  (first (foreign-invoke-query (qtop mgr "list-deck-ids") player-id)))

