(ns slifin.card-game
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.path :refer [ALL keypath term termval nil->val]]
    [com.rpl.rama.aggs :as aggs]))

;; wrap interop for use in dataflow
(defn now-ts [] (System/currentTimeMillis))

;;; Game helpers (pure functions) ;;;

;; Cards are integers 1–52. Value for comparison is 1–13 (Ace low),
;; cycling across the four suits.
(defn card-value [card]
  (inc (mod (dec card) 13)))

(defn initial-game [player1-id player2-id]
  (let [deck (shuffle (range 1 53))]
    {:player1-id    player1-id
     :player2-id    player2-id
     :player1-hand  (vec (take 26 deck))
     :player2-hand  (vec (drop 26 deck))
     :player1-score 0
     :player2-score 0
     :round         0
     :status        "in-progress"
     :winner-id     nil}))

(defn advance-round
  "Given the current game map, plays one round: each player reveals their top
  card, the higher value wins a point (ties score nobody), and the game is
  marked complete once all 26 cards have been played."
  [game]
  (if (not= "in-progress" (:status game))
    game
    (let [p1-card  (first (:player1-hand game))
          p2-card  (first (:player2-hand game))
          p1-val   (card-value p1-card)
          p2-val   (card-value p2-card)
          updated  (-> game
                       (update :player1-hand (comp vec rest))
                       (update :player2-hand (comp vec rest))
                       (update :round inc)
                       (cond-> (> p1-val p2-val) (update :player1-score inc))
                       (cond-> (> p2-val p1-val) (update :player2-score inc)))]
      (if (empty? (:player1-hand updated))
        (let [{:keys [player1-score player2-score player1-id player2-id]} updated]
          (assoc updated
                 :status    "complete"
                 :winner-id (cond
                              (> player1-score player2-score) player1-id
                              (> player2-score player1-score) player2-id
                              :else nil)))
        updated))))

(defn advance-round-with-choices
  "Like advance-round but each player plays the card at their chosen index
  rather than the top of their hand."
  [game p1-idx p2-idx]
  (if (not= "in-progress" (:status game))
    game
    (let [p1-card  (nth (:player1-hand game) p1-idx)
          p2-card  (nth (:player2-hand game) p2-idx)
          p1-val   (card-value p1-card)
          p2-val   (card-value p2-card)
          rm       (fn [hand idx] (vec (concat (take idx hand) (drop (inc idx) hand))))
          updated  (-> game
                       (update :player1-hand rm p1-idx)
                       (update :player2-hand rm p2-idx)
                       (update :round inc)
                       (cond-> (> p1-val p2-val) (update :player1-score inc))
                       (cond-> (> p2-val p1-val) (update :player2-score inc)))]
      (if (empty? (:player1-hand updated))
        (let [{:keys [player1-score player2-score player1-id player2-id]} updated]
          (assoc updated
                 :status    "complete"
                 :winner-id (cond
                              (> player1-score player2-score) player1-id
                              (> player2-score player1-score) player2-id
                              :else nil)))
        updated))))

(defn player-hand-key [player-num]
  (if (= 1 player-num) :player1-hand :player2-hand))

(defmodule CardGameModule
  [setup topologies]
  ;; Depots
  (declare-depot setup *player-cmds (hash-by :player-id))
  (declare-depot setup *deck-cmds (hash-by :player-id))
  (declare-depot setup *game-cmds (hash-by :game-id))

  (let [s (stream-topology topologies "core")]
    ;; PStates
    (declare-pstate s $$players
                    {Long (fixed-keys-schema {:name String :created-at Long})})
    (declare-pstate s $$decks
                    {Long (fixed-keys-schema {:player-id Long :name String :created-at Long})})
    (declare-pstate s $$player-decks
                    {Long (set-schema Long {:subindex? true})})
    ;; Stores the full game state as an opaque value so that (term advance-round)
    ;; can receive and return a plain Clojure map with plain Clojure vectors.
    (declare-pstate s $$games
                    {Long java.lang.Object})

    ;; ETL / sources
    (<<sources s
      ;; players
      (source> *player-cmds :> *pcmd)
      (select> [(keypath :op)] *pcmd :> *op)
      (select> [(keypath :player-id)] *pcmd :> *player-id)
      (select> [(keypath :name)] *pcmd :> *name)
      (select> [(keypath :ts)] *pcmd :> *ts)
      (<<shadowif *ts nil? (now-ts))
      (<<switch *op
                (case> :create-player)
                (|hash *player-id)
                (local-transform> [(keypath *player-id)
                                   (termval {:name       *name
                                             :created-at *ts})]
                  $$players))

      ;; decks
      (source> *deck-cmds :> *dcmd)
      (select> [(keypath :op)] *dcmd :> *dop)
      (select> [(keypath :player-id)] *dcmd :> *player-id)
      (select> [(keypath :deck-id)] *dcmd :> *deck-id)
      (select> [(keypath :name)] *dcmd :> *name)
      (select> [(keypath :ts)] *dcmd :> *ts)
      (<<shadowif *ts nil? (now-ts))
      (<<switch *dop
                (case> :create-deck)
                (|hash *deck-id)
                (local-transform> [(keypath *deck-id)
                                   (termval {:player-id  *player-id
                                             :name       *name
                                             :created-at *ts})]
                  $$decks)

                (|hash *player-id)
                (+compound $$player-decks {*player-id (aggs/+set-agg *deck-id)}))

      ;; games
      (source> *game-cmds :> *gcmd)
      (select> [(keypath :op)] *gcmd :> *gop)
      (select> [(keypath :game-id)] *gcmd :> *game-id)
      (<<switch *gop
                (case> :create-game)
                (select> [(keypath :player1-id)] *gcmd :> *player1-id)
                (select> [(keypath :player2-id)] *gcmd :> *player2-id)
                (initial-game *player1-id *player2-id :> *game-state)
                (|hash *game-id)
                (local-transform> [(keypath *game-id) (termval *game-state)] $$games)

                (case> :play-round)
                (|hash *game-id)
                (local-transform> [(keypath *game-id) (term advance-round)] $$games)

                (case> :play-cards)
                (select> [(keypath :p1-card-idx)] *gcmd :> *p1-idx)
                (select> [(keypath :p2-card-idx)] *gcmd :> *p2-idx)
                (|hash *game-id)
                (local-select> [(keypath *game-id)] $$games :> *cur-game)
                (advance-round-with-choices *cur-game *p1-idx *p2-idx :> *new-game)
                (local-transform> [(keypath *game-id) (termval *new-game)] $$games))))


  (<<query-topology topologies "get-player" [*player-id :> *player]
    (|hash *player-id)
    (local-select> [(keypath *player-id) (nil->val nil)] $$players :> *player)
    (|origin))

  (<<query-topology topologies "list-deck-ids" [*player-id :> *deck-ids]
    (|hash *player-id)
    (local-select> [(keypath *player-id) ALL] $$player-decks :> *deck-id)
    (|origin)
    (aggs/+set-agg *deck-id :> *deck-ids))

  (<<query-topology topologies "list-decks" [*player-id :> *decks]
    ;; PRE-AGG: start on the player's shard
    (|hash *player-id)
    (local-select> [(keypath *player-id) ALL] $$player-decks :> *deck-id)

    ;; hop to the deck's shard to read the deck row
    (|hash *deck-id)
    (local-select> [(keypath *deck-id)] $$decks :> *deck)

    (assoc *deck :deck-id *deck-id :> *row)

    ;; POST-AGG: move to origin, then aggregate to a single result
    (|origin)
    (aggs/+vec-agg *row :> *decks))

  (<<query-topology topologies "get-game" [*game-id :> *game]
    (|hash *game-id)
    (local-select> [(keypath *game-id) (nil->val nil)] $$games :> *game)
    (|origin))

  (<<query-topology topologies "get-hand" [*game-id *player-num :> *hand]
    (|hash *game-id)
    (local-select> [(keypath *game-id) (nil->val nil)] $$games :> *game)
    (player-hand-key *player-num :> *hand-key)
    (get *game *hand-key :> *hand)
    (|origin)))
