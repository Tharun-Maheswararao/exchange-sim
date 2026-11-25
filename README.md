# Distributed Exchange Simulator

A small stock exchange built to be **provably correct under failure**. One day of real Nasdaq ITCH messages is replayed as client order flow, through a gateway with inline risk checks, into sharded, deterministic matching engines. Each shard runs a primary and a hot standby fenced by Kafka transactional producer epochs.

After every chaos test, a verifier proves:
- no order was lost,
- no fill was duplicated,
- every client received every report meant for it,
- the primary, the standby, an independently written golden model and a C++ ITCH feed handler all reach the same state hash.

The full design and the week-by-week workplan are in [`docs/plan.md`](docs/plan.md).

## Status

| Milestone | State |
|---|---|
| Week 0: specs, repo, CI, Compose skeleton | Done |
| Week 1: matching engine, golden model, hash chain, digests, snapshots | Done; 49 tests passing |
| Week 2: protocol, gateway, Kafka engine node, replayer | Next |

## What exists today

- **`engine-core`:** deterministic price-time matching engine with no dependencies.
  - LIMIT, MARKET (5% collar), IOC, cancel and cancel-replace with priority rules.
  - Self-trade prevention, halts, mass cancel, end of day.
  - Duplicate detection with per-session high-water marks.
  - XXH64 output hash chain, per-symbol and shard book digests.
  - Canonical, digest-verified snapshots.
- **`golden`:** reference engine written from the spec with flat lists and linear scans. It shares no matching logic with the real engine.
- **Tests:**
  - 36 spec scenarios, each run on both engines with outputs, chain and full state compared after every event.
  - Differential tests: more than a million random events per run through both engines.
  - Coverage guard: proves the random flow hits every engine path.
  - Determinism tests: repeat runs, concurrent engines, snapshot and restore at arbitrary points.
  - XXH64 reference vectors.

## Build and test

Requires JDK 21 or newer. The Gradle wrapper downloads Gradle itself.

```bash
./gradlew build
```

## Layout

```
spec/            matching rules, encodings and digests, order entry protocol, ITCH mapping
engine-core/     book, matching, hash chain, digests, snapshots (no dependencies)
golden/          reference engine and the cross-engine test suites
deploy/compose/  local stack: 3 KRaft brokers, Postgres, Prometheus
docs/            project plan and workplan
```

## Specifications

- [`spec/matching.md`](spec/matching.md): validation order, output order, priority rules, scenario catalogue.
- [`spec/digest.md`](spec/digest.md): byte-level encodings, hash chain, book and state digests, snapshot format.
- [`spec/protocol.md`](spec/protocol.md): binary order entry protocol, resume and watermarks (draft).
- [`spec/itch-mapping.md`](spec/itch-mapping.md): ITCH message → client order mapping, divergence handling (draft).
