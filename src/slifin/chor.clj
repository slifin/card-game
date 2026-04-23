(ns slifin.chor
  (:require
   [clojure.string :as str]
   [klor.core :refer :all]
   [klor.runtime :refer [play-role]]
   [klor.simulator :refer [simulate-chor]]
   [klor.sockets :refer [wrap-sockets with-server with-accept with-client]]
   [slifin.client :as client]
   [slifin.card-game :refer [card-value]]))

;;; Rama connection ;;;
;; Must be bound (via `binding`) before calling `play-role` or `simulate-chor`.
;; All Rama calls in the choreography are inside `(P1 ...)` blocks, so only P1
;; ever dereferences this — P2 can leave it nil.
(def ^:dynamic *ipc* nil)

;;; Display helpers (pure, same result at both roles when inputs are agree-type) ;;;

(defn- suit-str [card]
  (case (quot (dec card) 13) 0 "♣" 1 "♦" 2 "♥" 3 "♠"))

(defn- rank-str [v]
  (case v 1 "A" 11 "J" 12 "Q" 13 "K" (str v)))

(defn- card-str [card]
  (str (rank-str (card-value card)) (suit-str card)))

(defn- display-hand
  "Returns a formatted string of the player's hand with 1-based indices."
  [hand name]
  (str/join "\n"
            (cons (format "%s's hand  (%d cards remaining):" name (count hand))
                  (map-indexed (fn [i card]
                                 (format "  [%d] %s  (value %d)"
                                         (inc i) (card-str card) (card-value card)))
                               hand))))

(defn- pick-card!
  "Prompts the player to choose a card from hand. Returns a 0-based index."
  [hand name]
  (println (display-hand hand name))
  (loop []
    (print (format "Choose a card [1-%d]: " (count hand)))
    (flush)
    (let [n (try (Long/parseLong (str/trim (read-line)))
                 (catch Exception _ 0))]
      (if (<= 1 n (count hand))
        (dec n)
        (do (println "  Invalid — try again.") (recur))))))

(defn- round-line [round p1-name p1-card p2-name p2-card p1-score p2-score]
  (let [p1v    (card-value p1-card)
        p2v    (card-value p2-card)
        result (cond (> p1v p2v) (str p1-name " wins the round!")
                     (> p2v p1v) (str p2-name " wins the round!")
                     :else       "Tie!")]
    (str/join "\n"
              [(format "  %s played %s  (value %d)"  p1-name (card-str p1-card) p1v)
               (format "  %s played %s  (value %d)"  p2-name (card-str p2-card) p2v)
               (format "  → %s" result)
               (format "  Scores  →  %s: %d  |  %s: %d" p1-name p1-score p2-name p2-score)])))

;;; Choreographies ;;;

;; game-loop: one recursive call per round.
;;
;; Each player fetches their own private hand from Rama, then picks a card.
;; Choices are exchanged via Klor (P1 picks first; P2 then commits their pick).
;; P1 submits both picks to Rama; the updated game state is broadcast to both.
(defchor game-loop [P1 P2]
  (-> #{P1 P2} #{P1 P2} #{P1 P2} #{P1 P2})
  [p1-name p2-name game-id]
  ;; P1 fetches both hands from Rama (P1 is the sole Rama accessor).
  ;; P2's hand is sent one-way so it's P2-local in the choreography type.
  (let [p1-hand (P1 (client/get-hand *ipc* game-id 1))
        p2-hand (P1->P2 (P1 (client/get-hand *ipc* game-id 2)))]
    ;; Card selection.
    ;; P1 picks → choice broadcast to P2 (P1=>P2 → agree type).
    ;; P2 then picks → choice broadcast to P1 (P2=>P1 → agree type).
    ;; Note: in a sequential protocol one player always sees the other's
    ;; committed choice first; for this demo that's acceptable.
    (let [p1-idx (P1=>P2 (P1 (pick-card! p1-hand p1-name)))
          p2-idx (P2=>P1 (P2 (pick-card! p2-hand p2-name)))]
      ;; Share the actual cards chosen so both can see the reveal.
      (let [p1-card (P1=>P2 (P1 (nth p1-hand p1-idx)))
            p2-card (P2=>P1 (P2 (nth p2-hand p2-idx)))]
        ;; P1 submits both picks to Rama.
        (P1 (client/play-cards! *ipc* {:game-id     game-id
                                       :p1-card-idx p1-idx
                                       :p2-card-idx p2-idx}))
        ;; Broadcast updated game state to both roles.
        (let [after (P1=>P2 (P1 (client/get-game *ipc* game-id)))
              line  (round-line (:round after)
                                p1-name p1-card
                                p2-name p2-card
                                (:player1-score after)
                                (:player2-score after))]
          (P1 (println (str "\n--- Round " (:round after) " ---\n" line)))
          (P2 (println (str "\n--- Round " (:round after) " ---\n" line)))
          (if (= (:status after) "complete")
            (let [winner-id (:winner-id after)
                  msg       (cond
                              (nil? winner-id) "It's a tie!"
                              (= winner-id 1)  (str p1-name " wins the game!")
                              :else            (str p2-name " wins the game!"))]
              (P1 (println (str "\n=== Game Over ===\n" msg)))
              (P2 (println (str "\n=== Game Over ===\n" msg)))
              msg)
            (game-loop [P1 P2] p1-name p2-name game-id)))))))

;; card-game: top-level entry point.
;; No parameters — all state flows from user input and Rama.
(defchor card-game [P1 P2]
  (-> #{P1 P2})
  []
  (let [p1-name (P1=>P2 (P1 (do (print "Your name (Player 1): ") (flush) (str/trim (read-line)))))
        p2-name (P2=>P1 (P2 (do (print "Your name (Player 2): ") (flush) (str/trim (read-line)))))
        game-id (P1=>P2 (P1 (let [gid (+ 1000 (rand-int 9000))]
                               (client/create-game! *ipc* {:game-id    gid
                                                           :player1-id 1
                                                           :player2-id 2})
                               gid)))
        header  (format "\nGame started!  %s (P1)  vs  %s (P2)\n%s"
                        p1-name p2-name (apply str (repeat 50 "-")))]
    (P1 (println header))
    (P2 (println header))
    (game-loop [P1 P2] p1-name p2-name game-id)))
