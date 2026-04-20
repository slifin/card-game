(ns slifin.card-game-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            slifin.card-game
            [slifin.client :as client]))

(defn with-module [f]
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})
    (f ipc)))

(deftest create-player-test
  (testing "creating a player stores name and timestamp"
    (with-module
      (fn [ipc]
        (client/create-player! ipc {:player-id 1 :name "Alice" :ts 1000})
        (let [player (client/get-player ipc 1)]
          (is (= "Alice" (:name player)))
          (is (= 1000 (:created-at player)))))))

  (testing "querying a non-existent player returns nil"
    (with-module
      (fn [ipc]
        (is (nil? (client/get-player ipc 999)))))))

(deftest create-deck-test
  (testing "creating a deck links it to the player"
    (with-module
      (fn [ipc]
        (client/create-player! ipc {:player-id 2 :name "Bob" :ts nil})
        (client/create-deck! ipc {:player-id 2 :deck-id 101 :name "Starter Deck" :ts nil})
        (let [deck-ids (client/list-deck-ids ipc 2)
              decks    (client/list-decks ipc 2)]
          (is (contains? deck-ids 101))
          (is (= 1 (count decks)))
          (is (= "Starter Deck" (:name (first decks))))
          (is (= 2 (:player-id (first decks)))))))))

(deftest multiple-decks-test
  (testing "a player can have multiple decks"
    (with-module
      (fn [ipc]
        (client/create-player! ipc {:player-id 3 :name "Charlie" :ts nil})
        (client/create-deck! ipc {:player-id 3 :deck-id 201 :name "Deck A" :ts nil})
        (client/create-deck! ipc {:player-id 3 :deck-id 202 :name "Deck B" :ts nil})
        (is (= #{201 202} (client/list-deck-ids ipc 3)))))))

(deftest empty-deck-list-test
  (testing "a player with no decks returns empty collections"
    (with-module
      (fn [ipc]
        (client/create-player! ipc {:player-id 4 :name "Dave" :ts nil})
        (is (empty? (client/list-deck-ids ipc 4)))
        (is (empty? (client/list-decks ipc 4)))))))
