(ns slifin.card-game
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer [keypath termval]]))


(defmodule CardsModule [setup topologies]
  (declare-depot setup *player-depot :random)

  (let [s (stream-topology topologies "cards-topology")]
    (declare-pstate s $$players {Long (fixed-keys-schema {:name String})})))