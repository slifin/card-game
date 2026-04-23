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
;; Both roles run in the same process, communicating through Klor's in-memory
;; simulator. One terminal, one Rama IPC, both "players" take turns at stdin.
;; ---------------------------------------------------------------------------
(defn play-local []
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})
    (binding [chor/*ipc* ipc]
      @(simulate-chor card-game))))

;; ---------------------------------------------------------------------------
;; play-as-p1 / play-as-p2
;;
;; Two-process socket transport.  Open two terminals in the project root and:
;;
;;   Terminal 1:  clojure -M:provided -m slifin.play p1
;;   Terminal 2:  clojure -M:provided -m slifin.play p2
;;
;; P1 hosts the Rama module in-process and opens a TCP server on port 7171.
;; P2 connects to that port.  All Rama operations live inside (P1 ...) blocks
;; in the choreography, so P2 never needs a Rama connection of its own.
;; ---------------------------------------------------------------------------
(defn play-as-p1 []
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})
    (binding [chor/*ipc* ipc]
      (with-server [srv {:port port}]
        (println (str "Waiting for Player 2 on port " port "..."))
        (with-accept [srv p2-conn]
          (println "Player 2 connected — starting game!\n")
          (play-role (wrap-sockets {:role 'P1} {'P2 p2-conn})
                     card-game))))))

(defn play-as-p2 []
  (println (str "Connecting to Player 1 on port " port "..."))
  (with-client [p1-conn {:port port}]
    (println "Connected — starting game!\n")
    (play-role (wrap-sockets {:role 'P2} {'P1 p1-conn})
               card-game)))

;; ---------------------------------------------------------------------------
;; -main
;; ---------------------------------------------------------------------------
(defn -main [& [mode]]
  (case mode
    "local" (play-local)
    "p1"    (play-as-p1)
    "p2"    (play-as-p2)
    (do (println "Usage: clojure -M:provided -m slifin.play [local|p1|p2]")
        (println)
        (println "  local  — single process, both roles share one terminal")
        (println "  p1     — Player 1 (hosts Rama + TCP server on port" (str port ")"))
        (println "  p2     — Player 2 (connects to Player 1)")
        (System/exit 1))))
