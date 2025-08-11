(ns slifin.card-game
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.path :refer [keypath termval]]
    [com.rpl.rama.aggs :as aggs]))

(defmodule CardGameModule [setup topologies]
  ;; Depots
  (declare-depot setup *player-cmds (hash-by :player-id))
  (declare-depot setup *deck-cmds   (hash-by :player-id))

  ;; Stream topology block
  (let [s (stream-topology topologies "core")]
    ;; PStates
    (declare-pstate s $$players
                    {Long (fixed-keys-schema {:name String :created-at Long})})
    (declare-pstate s $$decks
                    {Long (fixed-keys-schema {:player-id Long :name String :created-at Long})})
    (declare-pstate s $$player-decks
                    {Long (set-schema Long {:subindex? true})})

    ;; ETL / sources
    (<<sources s
      (source> *player-cmds :> {:keys [*op *player-id *name *ts]})
      (|hash *player-id)
      (<<switch *op
                (case> :create-player)
                (local-transform> [(keypath *player-id)
                                   (termval {:name *name
                                             :created-at (or *ts (System/currentTimeMillis))})]
                  $$players))

      (source> *deck-cmds :> {:keys [*op *player-id *deck-id *name *ts]})
      (|hash *player-id)
      (<<switch *op
                (case> :create-deck)
                (local-transform> [(keypath *deck-id)
                                   (termval {:player-id *player-id
                                             :name *name
                                             :created-at (or *ts (System/currentTimeMillis))})]
                  $$decks)
                (+compound $$player-decks
                           {*player-id (aggs/+set-agg *deck-id)})))

    ;; Queries can remain outside the let
    (<<query-topology topologies "get-player" [*player-id]
      (|hash$$ $$players *player-id)
      (local-select> [(keypath *player-id)] $$players :> *profile)
      (:> *profile))

    (<<query-topology topologies "list-deck-ids" [*player-id]
      (|hash$$ $$player-decks *player-id)
      (local-select> [(keypath *player-id)] $$player-decks :> *ids)
      (:> *ids)))))
