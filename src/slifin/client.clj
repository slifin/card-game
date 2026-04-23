(ns slifin.client
  (:require [com.rpl.rama :as r :refer :all]
            [com.rpl.rama.test :as rtest]
            slifin.card-game))

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
  (foreign-invoke-query (qtop mgr "get-player") player-id))

(defn list-deck-ids [mgr player-id]
  (foreign-invoke-query (qtop mgr "list-deck-ids") player-id))

(defn list-decks [mgr player-id]
  (foreign-invoke-query (qtop mgr "list-decks") player-id))

(defn create-game! [mgr {:keys [game-id player1-id player2-id]}]
  (foreign-append! (depot mgr "*game-cmds")
                   {:op :create-game :game-id game-id :player1-id player1-id :player2-id player2-id}))

(defn play-round! [mgr {:keys [game-id]}]
  (foreign-append! (depot mgr "*game-cmds")
                   {:op :play-round :game-id game-id}))

(defn get-game [mgr game-id]
  (foreign-invoke-query (qtop mgr "get-game") game-id))

(defn play-cards! [mgr {:keys [game-id p1-card-idx p2-card-idx]}]
  (foreign-append! (depot mgr "*game-cmds")
                   {:op :play-cards :game-id game-id
                    :p1-card-idx p1-card-idx :p2-card-idx p2-card-idx}))

(defn get-hand [mgr game-id player-num]
  (foreign-invoke-query (qtop mgr "get-hand") game-id player-num))


(comment
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})
    (create-player! ipc {:player-id 1 :name "Adrian" :ts nil})
    (create-deck! ipc {:player-id 1 :deck-id 1001 :name "Main" :ts nil})
    (Thread/sleep 200)
    (println :player (get-player ipc 1))
    (println :deck (list-decks ipc 1))))
