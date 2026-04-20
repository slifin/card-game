(ns slifin.card-game
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.path :refer [ALL keypath termval nil->val]]
    [com.rpl.rama.aggs :as aggs]))

;; wrap interop for use in dataflow
(defn now-ts [] (System/currentTimeMillis))

(defmodule CardGameModule
  [setup topologies]
  ;; Depots
  (declare-depot setup *player-cmds (hash-by :player-id))
  (declare-depot setup *deck-cmds (hash-by :player-id))

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
                (+compound $$player-decks {*player-id (aggs/+set-agg *deck-id)}))))


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
    ;; PRE-AGG: start on the player’s shard
    (|hash *player-id)
    (local-select> [(keypath *player-id) ALL] $$player-decks :> *deck-id)

    ;; hop to the deck’s shard to read the deck row
    (|hash *deck-id)
    (local-select> [(keypath *deck-id)] $$decks :> *deck)

    ;; build a plain map that also includes deck-id
    (assoc *deck :deck-id *deck-id :> *row)

    ;; POST-AGG: move to origin, then aggregate to a single result
    (|origin)
    (aggs/+vec-agg *row :> *decks)))

