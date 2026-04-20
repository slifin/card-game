# Card Game

A backend service for managing players and their card decks. Built in Clojure using [Rama](https://redplanetlabs.com/rama), a framework for building stateful distributed systems.

## What does this do?

It stores players and their card decks. You can:

- Create a player
- Create a deck and assign it to a player
- Look up a player by ID
- List a player's decks

## What is Rama?

Normally, building a backend service requires wiring together several separate systems: a message queue (like Kafka) to receive commands, a stream processor (like Flink) to act on them, and a database (like Postgres) to store the results. Each needs to be deployed, scaled, and kept in sync separately.

Rama replaces all of that with a single program. You define:

- **Depots** — append-only logs that accept incoming commands (like a message queue)
- **Stream topologies** — code that reacts to depot entries and updates stored state (like a stream processor)
- **PStates** — persistent, indexed state that gets queried (like a database)
- **Query topologies** — named queries over PStates (like stored procedures)

Everything runs inside one Rama module, which Rama handles distributing across machines.

## Project structure

```
src/slifin/
  card_game.clj   — the Rama module: depots, state, and query definitions
  client.clj      — functions for interacting with the module from Clojure code

test/slifin/
  card_game_test.clj — tests using an in-process Rama cluster
```

## How it works

### Writing data

Two depots accept commands:

| Depot | Accepts |
|-------|---------|
| `*player-cmds` | `{:op :create-player, :player-id, :name, :ts}` |
| `*deck-cmds`   | `{:op :create-deck, :player-id, :deck-id, :name, :ts}` |

When a command is appended to a depot, the stream topology picks it up and writes to the appropriate PState. The `:ts` field is optional — if `nil`, the current timestamp is used.

### Stored state (PStates)

| PState | Schema | Purpose |
|--------|--------|---------|
| `$$players` | `{player-id → {:name, :created-at}}` | Player records |
| `$$decks` | `{deck-id → {:player-id, :name, :created-at}}` | Deck records |
| `$$player-decks` | `{player-id → #{deck-id, ...}}` | Index: which decks belong to a player |

### Reading data

Three query topologies are available:

| Query | Input | Returns |
|-------|-------|---------|
| `get-player` | `player-id` | `{:name, :created-at}` or `nil` |
| `list-deck-ids` | `player-id` | `#{deck-id, ...}` |
| `list-decks` | `player-id` | `[{:deck-id, :player-id, :name, :created-at}, ...]` |

## Usage

The `slifin.client` namespace provides a Clojure API:

```clojure
(require '[slifin.client :as client])
(require '[com.rpl.rama.test :as rtest])
(require 'slifin.card-game)

(with-open [ipc (rtest/create-ipc)]
  (rtest/launch-module! ipc slifin.card-game/CardGameModule {:tasks 4 :threads 2})

  (client/create-player! ipc {:player-id 1 :name "Alice" :ts nil})
  (client/create-deck!   ipc {:player-id 1 :deck-id 101 :name "Main Deck" :ts nil})

  (client/get-player    ipc 1)    ;; => {:name "Alice", :created-at <ts>}
  (client/list-decks    ipc 1)    ;; => [{:deck-id 101, :name "Main Deck", ...}]
  (client/list-deck-ids ipc 1))   ;; => #{101}
```

The `rtest/create-ipc` call above spins up a lightweight in-process Rama cluster — the same thing used in the tests. For production, you would connect to a real deployed cluster via `open-cluster-manager` instead.

## Running the tests

Rama must be on the classpath (the `:provided` alias) alongside the test runner:

```bash
clojure -X:provided:test
```

## Dependencies

- [Clojure](https://clojure.org/) 1.12
- [Rama](https://redplanetlabs.com/rama) 1.1.0 — distributed backend framework
- [rama-helpers](https://github.com/redplanetlabs/rama-helpers) 0.10.0 — utility library for Rama
