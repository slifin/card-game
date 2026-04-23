(ns slifin.play
  (:require
   [com.rpl.rama.test :as rtest]
   [klor.runtime :refer [play-role]]
   [klor.simulator :refer [simulate-chor]]
   [klor.sockets :refer [wrap-sockets with-server with-accept with-client]]
   slifin.card-game
   [slifin.chor :as chor :refer [card-game]]))

(def ^:private port 7171)

;; ---------------------------------------------------------------------------
;; play-local
;;
;; All three roles (GM, P1, P2) run in one process via Klor's simulator.
;; Players are prompted sequentially in the same terminal.
;; ---------------------------------------------------------------------------
(defn play-local []
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})
    (binding [chor/*ipc* ipc]
      @(simulate-chor card-game))))

;; ---------------------------------------------------------------------------
;; Three-terminal socket transport
;;
;;   Terminal 1 (game master):  clojure -M:provided -m slifin.play gm
;;   Terminal 2 (player 1):     clojure -M:provided -m slifin.play p1
;;   Terminal 3 (player 2):     clojure -M:provided -m slifin.play p2
;;
;; GM hosts the Rama module in-process, listens on TCP port 7171, and accepts
;; connections from P1 and P2 (in that order).  Neither player has a Rama
;; connection — all state management happens at GM.
;;
;; Because P1 and P2 send their card choices to GM independently over separate
;; sockets, both players choose concurrently; neither can see the other's pick.
;; ---------------------------------------------------------------------------
(defn play-as-gm []
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})
    (binding [chor/*ipc* ipc]
      (with-server [srv {:port port}]
        (println (str "Game master ready — waiting for Player 1 on port " port "..."))
        (with-accept [srv [p1-conn p2-conn]]
          (println "Both players connected — starting game!")
          (play-role (wrap-sockets {:role 'GM} {'P1 p1-conn 'P2 p2-conn})
                     card-game))))))

(defn play-as-p1 []
  (println (str "Connecting to game master on port " port "..."))
  (with-client [gm-conn {:port port}]
    (println "Connected.")
    (play-role (wrap-sockets {:role 'P1} {'GM gm-conn})
               card-game)))

(defn play-as-p2 []
  (println (str "Connecting to game master on port " port "..."))
  (with-client [gm-conn {:port port}]
    (println "Connected.")
    (play-role (wrap-sockets {:role 'P2} {'GM gm-conn})
               card-game)))

;; ---------------------------------------------------------------------------
;; -main
;; ---------------------------------------------------------------------------
(defn -main [& [mode]]
  (case mode
    "local" (play-local)
    "gm"    (play-as-gm)
    "p1"    (play-as-p1)
    "p2"    (play-as-p2)
    (do (println "Usage: clojure -M:provided -m slifin.play [local|gm|p1|p2]")
        (println)
        (println "  local  — single process, all roles share one terminal")
        (println "  gm     — game master (hosts Rama + TCP server, connects P1 then P2)")
        (println "  p1     — Player 1 (connects to game master)")
        (println "  p2     — Player 2 (connects to game master)")
        (System/exit 1))))
