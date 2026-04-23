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

(deftest create-game-test
  (testing "a new game has two full hands and zero scores"
    (with-module
      (fn [ipc]
        (client/create-game! ipc {:game-id 1 :player1-id 10 :player2-id 20})
        (let [game (client/get-game ipc 1)]
          (is (= "in-progress" (:status game)))
          (is (= 0 (:round game)))
          (is (= 0 (:player1-score game)))
          (is (= 0 (:player2-score game)))
          (is (= 26 (count (:player1-hand game))))
          (is (= 26 (count (:player2-hand game))))
          ;; all 52 cards dealt, no duplicates
          (is (= (set (range 1 53))
                 (set (concat (:player1-hand game) (:player2-hand game)))))))))

  (testing "a non-existent game returns nil"
    (with-module
      (fn [ipc]
        (is (nil? (client/get-game ipc 999)))))))

(deftest play-round-test
  (testing "playing a round removes one card from each hand and increments the round"
    (with-module
      (fn [ipc]
        (client/create-game! ipc {:game-id 2 :player1-id 10 :player2-id 20})
        (client/play-round! ipc {:game-id 2})
        (let [game (client/get-game ipc 2)]
          (is (= 1 (:round game)))
          (is (= 25 (count (:player1-hand game))))
          (is (= 25 (count (:player2-hand game))))
          (is (= "in-progress" (:status game)))
          ;; exactly one point is awarded per round (or zero on a tie)
          (is (<= (+ (:player1-score game) (:player2-score game)) 1))))))

  (testing "calling play-round on a completed game is a no-op"
    (with-module
      (fn [ipc]
        (client/create-game! ipc {:game-id 3 :player1-id 10 :player2-id 20})
        (dotimes [_ 26] (client/play-round! ipc {:game-id 3}))
        (let [after-complete (client/get-game ipc 3)]
          (client/play-round! ipc {:game-id 3})
          (is (= after-complete (client/get-game ipc 3))))))))

(deftest complete-game-test
  (testing "playing all 26 rounds completes the game"
    (with-module
      (fn [ipc]
        (client/create-game! ipc {:game-id 4 :player1-id 10 :player2-id 20})
        (dotimes [_ 26] (client/play-round! ipc {:game-id 4}))
        (let [game (client/get-game ipc 4)]
          (is (= "complete" (:status game)))
          (is (= 26 (:round game)))
          (is (empty? (:player1-hand game)))
          (is (empty? (:player2-hand game)))
          ;; scores add up to at most 26 (ties score nobody)
          (is (<= (+ (:player1-score game) (:player2-score game)) 26))
          ;; winner is one of the two players, or nil for a tie
          (is (contains? #{10 20 nil} (:winner-id game))))))))

(deftest play-cards-test
  (testing "playing specific card indices removes those cards from each hand"
    (with-module
      (fn [ipc]
        (client/create-game! ipc {:game-id 5 :player1-id 10 :player2-id 20})
        (let [before (client/get-game ipc 5)
              p1-card (nth (:player1-hand before) 2)   ; pick 3rd card (index 2)
              p2-card (nth (:player2-hand before) 0)]  ; pick 1st card (index 0)
          (client/play-cards! ipc {:game-id 5 :p1-card-idx 2 :p2-card-idx 0})
          (let [after (client/get-game ipc 5)]
            (is (= 1 (:round after)))
            (is (= 25 (count (:player1-hand after))))
            (is (= 25 (count (:player2-hand after))))
            ;; chosen cards are gone from hands
            (is (not (some #{p1-card} (:player1-hand after))))
            (is (not (some #{p2-card} (:player2-hand after)))))))))

  (testing "get-hand returns only that player's cards"
    (with-module
      (fn [ipc]
        (client/create-game! ipc {:game-id 6 :player1-id 10 :player2-id 20})
        (let [game    (client/get-game ipc 6)
              p1-hand (client/get-hand ipc 6 1)
              p2-hand (client/get-hand ipc 6 2)]
          (is (= (:player1-hand game) p1-hand))
          (is (= (:player2-hand game) p2-hand))
          (is (empty? (clojure.set/intersection (set p1-hand) (set p2-hand)))))))))

