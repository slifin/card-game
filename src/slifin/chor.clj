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
;; Must be bound before calling play-role (GM process) or simulate-chor.
;; Only GM ever calls client functions — P1 and P2 never touch *ipc*.
(def ^:dynamic *ipc* nil)

;;; Display helpers ;;;

(defn- suit-str [card]
  (case (quot (dec card) 13) 0 "♣" 1 "♦" 2 "♥" 3 "♠"))

(defn- rank-str [v]
  (case v 1 "A" 11 "J" 12 "Q" 13 "K" (str v)))

(defn- card-str [card]
  (str (rank-str (card-value card)) (suit-str card)))

(defn- display-hand [hand name]
  (str/join "\n"
            (cons (format "\n%s's hand  (%d cards remaining):" name (count hand))
                  (map-indexed (fn [i card]
                                 (format "  [%d] %s  (value %d)"
                                         (inc i) (card-str card) (card-value card)))
                               hand))))

(defn- pick-card! [hand name]
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
              [(format "\n--- Round %d ---" round)
               (format "  %s played %s  (value %d)" p1-name (card-str p1-card) p1v)
               (format "  %s played %s  (value %d)" p2-name (card-str p2-card) p2v)
               (format "  → %s" result)
               (format "  Scores  →  %s: %d  |  %s: %d" p1-name p1-score p2-name p2-score)])))

;;; Choreographies ;;;
;;
;; Three roles:
;;   GM  — game master; holds the Rama connection; knows nothing the players
;;          shouldn't know until they've committed their choices
;;   P1  — player 1; sees only their own hand
;;   P2  — player 2; sees only their own hand
;;
;; Information flow per round:
;;   GM  →P1   private hand for P1
;;   GM  →P2   private hand for P2
;;   P1  →GM   chosen card index      (P2 does not see this)
;;   P2  →GM   chosen card index      (P1 does not see this)
;;   GM  →both  round result + scores
;;
;; Because P1 and P2 send their picks to GM independently, in the socket
;; transport both players choose concurrently — neither can see the other's
;; committed choice.

(defchor game-loop [GM P1 P2]
  (-> #{GM P1 P2} #{GM P1 P2} #{GM P1 P2} #{GM P1 P2})
  [p1-name p2-name game-id]
  ;; GM distributes private hands — each player sees only their own cards.
  (let [p1-hand (GM=>P1 (GM (client/get-hand *ipc* game-id 1)))
        p2-hand (GM=>P2 (GM (client/get-hand *ipc* game-id 2)))]
    ;; Players choose concurrently (socket transport) or sequentially (simulate).
    ;; Neither player sees the other's choice before committing their own.
    (let [p1-idx (P1=>GM (P1 (pick-card! p1-hand p1-name)))
          p2-idx (P2=>GM (P2 (pick-card! p2-hand p2-name)))]
      ;; GM resolves the round in Rama.
      (GM (client/play-cards! *ipc* {:game-id     game-id
                                     :p1-card-idx p1-idx
                                     :p2-card-idx p2-idx}))
      ;; GM reads the updated state, builds the round summary, and broadcasts
      ;; it to both players via chained GM=>P2 then GM=>P1.
      (let [after   (GM (client/get-game *ipc* game-id))
            p1-card (GM (nth p1-hand p1-idx))
            p2-card (GM (nth p2-hand p2-idx))
            summary (GM=>P1 (GM=>P2
                             (GM (round-line (:round after)
                                            p1-name p1-card
                                            p2-name p2-card
                                            (:player1-score after)
                                            (:player2-score after)))))
            status  (GM=>P1 (GM=>P2 (GM (:status after))))]
        (P1 (println summary))
        (P2 (println summary))
        (if (= status "complete")
          (let [winner-id (GM=>P1 (GM=>P2 (GM (:winner-id after))))
                msg       (cond
                            (nil? winner-id) "It's a tie!"
                            (= winner-id 1)  (str p1-name " wins the game!")
                            :else            (str p2-name " wins the game!"))]
            (P1 (println (str "\n=== Game Over ===\n" msg)))
            (P2 (println (str "\n=== Game Over ===\n" msg)))
            msg)
          (game-loop [GM P1 P2] p1-name p2-name game-id))))))

(defchor card-game [GM P1 P2]
  (-> #{GM P1 P2})
  []
  ;; Collect names: each player tells GM, GM redistributes to the other.
  (let [p1-name* (P1=>GM (P1 (do (print "Your name (Player 1): ") (flush) (str/trim (read-line)))))
        p1-name  (GM=>P2 p1-name*)   ; #{P1 GM} → #{GM P1 P2}
        p2-name* (P2=>GM (P2 (do (print "Your name (Player 2): ") (flush) (str/trim (read-line)))))
        p2-name  (GM=>P1 p2-name*)   ; #{P2 GM} → #{GM P1 P2}
        game-id  (GM=>P1 (GM=>P2     ; GM creates game, broadcasts id to both
                          (GM (let [gid (+ 1000 (rand-int 9000))]
                                (client/create-game! *ipc* {:game-id    gid
                                                            :player1-id 1
                                                            :player2-id 2})
                                gid))))
        header   (format "\nGame started!  %s (P1)  vs  %s (P2)\n%s"
                         p1-name p2-name (apply str (repeat 50 "-")))]
    (P1 (println header))
    (P2 (println header))
    (game-loop [GM P1 P2] p1-name p2-name game-id)))
