# Distributed Exchange Simulator

**Project plan and workplan** · version 2.2 · 25 Nov 2026

Part A describes what you are building and why each design choice is there. Part B is the week-by-week workplan, with tasks, exit criteria, risks and cut order.

---

# Part A: Project details

## 1. Summary

You build a small stock exchange.

- A **replayer** turns one day of real Nasdaq ITCH messages into client orders.
- The orders flow through a **gateway** with inline risk checks into sharded, deterministic **matching engines**.
- Each shard runs a **primary and a hot standby**, fenced through Kafka transactional producer epochs.
- The exchange publishes an **ITCH-style market data feed**, and your existing **C++ ITCH feed handler** consumes it to rebuild the books, as a trading firm would.

**What makes it stand out:** after every chaos test, a verifier proves that:
- no order was lost,
- no fill was duplicated,
- every client received every report meant for it,
- four independent parties reach the same state: the primary, the standby, a separately written golden model, and the C++ feed handler's rebuilt books.

## 2. Architecture

### 2.1 Components

| Component | Built with | Job |
|---|---|---|
| Order gateway | Java 21, Netty | Binary TCP sessions (logon, heartbeats, resume, watermarks); account ownership; validation; inline pre-trade risk (library); fast-path duplicate check; publishing to `orders`; streaming exec reports to clients |
| Risk control service | Spring Boot, PostgreSQL | Owns limits and publishes them on a compacted `risk-limits` topic; consumes fills exactly once into Postgres (positions, open orders) for rebuilds; kill switch; writes control messages into the log |
| Matching engine node | Plain Java, no framework | One process per shard role. Deterministic price-time matching, duplicate high-water marks, hash chain, digests, snapshots, primary/standby roles, promotion |
| Market data and query service | Spring Boot, PostgreSQL | Consumes `md-updates` and `exec-reports`; keeps books; serves an ITCH-style TCP feed with snapshots and gap recovery; stores fills exactly once; REST queries |
| C++ feed handler (existing project) | C++ | Subscribes to the exchange feed, rebuilds books, reports per-symbol book digests at end of day |
| Order flow replayer | Java | ITCH → client orders; open-loop sending (ITCH-paced or constant rate); reconnect and resend; sent log and response log |
| Verifier | Java | Post-run correctness checks (section 11) |
| Chaos harness | Bash + Java | Resets the environment, injects a fault at a random time, runs to end of day, runs the verifier, records JSON results |
| Event log | Kafka in KRaft mode, 3 brokers (Compose); Strimzi on Kubernetes | Topics in 2.3 |
| Platform | Docker Compose → kind → EKS (stretch), Terraform, Prometheus, Grafana, GitHub Actions | Deploy, observe, benchmark, tear down |

Risk runs inside the gateway so latency numbers measure the exchange, not an extra network hop. The risk service owns the limits and the kill switch; the checks run in memory in the gateway.

### 2.2 Order path

```
Client ──TCP──▶ Gateway ──(risk check, in memory)──▶ Kafka `orders` [partition = shard]
                   ▲                                    ▲    │
      `risk-limits`│        Risk service (MassCancel,   │    │
      (compacted)  └──────── Halt, EndOfDay) ───────────┘    │
                                  ┌──────────────────────────┴──────────────────┐
                                  ▼                                             ▼
                           Engine PRIMARY                                 Engine STANDBY
                 (matches; publishes outputs + chain             (applies same input; buffers outputs;
                  checkpoint in one Kafka transaction)            trims from committed outputs; compares
                                  │                               checkpoints; takes snapshots)
                                  ▼
                    Kafka `exec-reports`, `md-updates` (read_committed)
                                  │
         ┌───────────────┬────────┴─────────┬──────────────────┐
         ▼               ▼                  ▼                  ▼
      Gateways     MD/query service    Risk service        Standby
  (acks, resume)  (Postgres, feed)   (positions sink)  (buffer trim, hash compare)
                         │
                         ▼
                 C++ feed handler (rebuilds books, end-of-day digests)
```

### 2.3 Kafka topics

| Topic | Partitioning | Written by | Contents |
|---|---|---|---|
| `orders` | Explicit partition = shard id, from a static symbol-to-shard table | Gateways and the risk service (idempotent producers) | Client orders and control messages. The partition offset is the official sequence number |
| `exec-reports` | Partition = shard id | Engine primary, transactional | Acks, fills, rejects, cancels, duplicate notices, `ChainCheckpoint` records. Every record carries `srcOffset` |
| `md-updates` | Partition = shard id | Engine primary, transactional | Order-level book events and trades with per-symbol sequence numbers; `BookDigest` at end of day |
| `risk-limits` | Compacted, key = account | Risk service | Per-account limits and the kill-switch `blocked` flag |

The symbol-to-shard table is fixed for a run. A different shard count means a new run with new topics.

### 2.4 Kafka configuration

- KRaft mode, with one pinned Kafka version recorded in the README. Transaction behaviour has changed between releases.
- **3 brokers in every environment**, Compose included.
- `default.replication.factor=3`, `min.insync.replicas=2`, `unclean.leader.election.enable=false`.
- `transaction.state.log.replication.factor=3`, `transaction.state.log.min.isr=2`.
- Producers: `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection` ≤ 5.
- Engine producer: `transaction.timeout.ms` well above the longest batch (for example 10 s).
- Output consumers: `isolation.level=read_committed`.
- Retention as in section 6.

---

## 3. Order entry protocol

### 3.1 Messages

Binary, little-endian, fixed layout, length-prefixed (hand-rolled codec or SBE). Prices are `int64` with 4 implied decimal places, as in ITCH. There is no floating point anywhere in the system.

**Session, inbound:**
- `Logon(sessionId, credentials, resumeVector[shard → lastOffset])`
- `Heartbeat`
- `Logout`

**Orders, inbound:**
- `NewOrder(clOrdId, symbol, side, qty, price, type: LIMIT | MARKET | IOC)`
- `Cancel(clOrdId, origClOrdId)`
- `CancelReplace(clOrdId, origClOrdId, newQty, newPrice)`

**Outbound:**
- `LogonAck`, `Heartbeat`
- `Accepted`, `Fill`, `Cancelled`, `Replaced`
- `Rejected(reason)`, `CancelRejected(reason)`
- `DuplicateIgnored(clOrdId)`, `Busy(clOrdId)`
- `Watermark(shard, offset)`

Every outbound report produced by an engine carries `(shard, offset)`.

**clOrdId rule:** a `uint64` that strictly increases per session. Gaps are allowed. Cancels and cancel-replaces use fresh clOrdIds as well.

### 3.2 Resume: exactly-once from the client's view

- The client tracks, per shard, the highest `exec-reports` offset it has received.
- On reconnect, to any gateway, `Logon` carries that resume vector. The gateway seeks each `exec-reports` partition to the client's offset, replays that session's reports, then switches the session to live.
- The client drops any report whose `(shard, offset)` is at or below what it has already seen, so replays and live traffic overlap safely.
- **Watermarks:** about once a second, the gateway sends each session a `Watermark(shard, offset)` for every shard: the highest `exec-reports` offset the gateway has fully processed on that shard. It travels on the session's own ordered channel, after any of that session's reports up to that offset, so the client can advance its vector safely. A session that has had no reports from a shard for hours still resumes from a recent offset, which keeps the resume scan short.
- **First logon:** an empty vector starts each shard at the current end of the partition. A new session has no orders yet, so it cannot miss anything.
- After resuming, the client resends every order it never got a response for, in original order, with the **same** clOrdId. The engine's duplicate check drops copies that already landed (4.4).
- Orders rejected with `Busy` never reached the log. The client retries them with a **new** clOrdId and records the link in its response log. Reusing the old id would fall at or below the high-water mark and be wrongly treated as a duplicate.

### 3.3 Exec-report delivery to gateways

Each gateway reads every `exec-reports` partition with `assign()` (no consumer group), `read_committed`, and forwards records for the sessions it holds. At 1–4 gateways this fan-out is affordable, and section 15 measures it.

While a paused zombie primary's transaction is open, the last stable offset stalls, so `read_committed` consumers wait until the new primary's `initTransactions()` aborts that transaction. This wait is part of the measured failover time.

---

## 4. Matching engine

### 4.1 Matching rules (`spec/matching.md`)

- **Order types:**
  - LIMIT rests on the book.
  - IOC cancels any remainder.
  - MARKET is converted to an IOC at the best opposite price ± the collar.
- **Collar:** market orders execute no further than 5% from the best opposite price on arrival; the remainder is cancelled. A market order that arrives with nothing on the opposite side is rejected.
- **Priority:** price first, then `orders` offset. Gateway timestamps are informational only.
- **Cancel-replace:**
  - Reducing quantity at the same price keeps priority.
  - Raising quantity or changing price loses priority; the order is treated as a new arrival at this offset.
  - Replacing to a quantity at or below the filled quantity cancels the order.
- **Tick size:** $0.01 at or above $1.00, $0.0001 below. Lot size 1 share. Off-tick prices are rejected.
- **Self-trade prevention:** cancel the aggressor's remaining quantity.
- **Halt:** while a symbol is halted, new orders and replaces are rejected and cancels are accepted.
- **IDs:**
  - Engine order id = `(shard, offset)`.
  - Fill id = `(shard, srcOffset, seqWithinEvent)`.
  - Per-symbol market data sequence numbers live in engine state.

### 4.2 Determinism rules

- The only input is one `orders` partition, in offset order.
- No wall clock in matching logic. Event timestamps come from the record and are only copied into outputs.
- No `HashMap`/`HashSet` iteration that affects outputs. Use sorted maps or arrays.
- A single-threaded matching loop. Kafka I/O runs on other threads, handing off through bounded queues.
- Canonical snapshot serialization, with a format version field.
- **Tested:** running the same input twice with different thread counts and JVM flags gives identical hash chains.

### 4.3 Hash chain, digests and telemetry

- **Output chain:** `h_n = xxHash64(h_{n-1} ‖ offset_n ‖ canonical(outputs_n))` after every input event, including events that produce no output.
- **Only business fields are hashed.** Telemetry (engine receive, match and commit times) goes in Kafka record headers, never in the hashed payload.
- **ChainCheckpoint:** each primary transaction ends with a `ChainCheckpoint{lastSrcOffset, h}` record on `exec-reports`.
- **Book digest, per symbol:** a canonical hash of one symbol's book only: price levels, and resting orders in priority order with engine order IDs and remaining quantities.
- **Shard book digest:** the hash of the sorted list of `(symbol, digest)` pairs. Comparisons use per-symbol digests, because the C++ handler sees every symbol and knows nothing about shards.
- **State digest:** shard book digest + duplicate high-water marks + market data sequences + halt flags. Stored in snapshots.

### 4.4 Duplicate detection

1. **Kafka idempotent producer** at the gateway: stops duplicates caused by producer retries.
2. **Engine high-water mark per (sessionId, shard):** part of replicated, snapshotted state. An order or cancel whose clOrdId is at or below the mark is a duplicate. The engine emits `DuplicateIgnored(clOrdId)` and changes nothing; the client gets the original outcome through resume (3.2). The engine never stores past acks, and state size is O(sessions × shards), not O(orders).
3. **Gateway fast-path cache** of the same marks: an optimization only.

**Why the mark is safe:** a session's orders reach each partition in send order, and resends replay in original order. A first copy therefore can never arrive after a higher clOrdId for the same shard.

**Gateway ordering rule (the mark depends on it):**
- A session's orders follow one first-in, first-out path from socket to Kafka: one Netty channel, one queue, and one idempotent producer per gateway (or producers assigned per session, never per message).
- No thread pool may reorder a session's sends.
- `max.in.flight.requests.per.connection` stays at 5 or below with idempotence on, so retries keep order.
- A test pushes interleaved sessions through the gateway pipeline and checks per-session order in each partition.

Across a gateway crash, a request from the dead gateway that the broker had already received can land after the new gateway's writes. It then falls at or below the mark and is discarded, which is correct, because the client has resent it.

### 4.5 Control messages in the log

Written into `orders` partitions by the risk service, so every replica and the golden model see them at the same offset.

- **`MassCancel(scope: account | session | all)`:** the kill switch writes this to every shard.
- **`Halt(symbol)`, `Resume(symbol)`.**
- **`EndOfDay`:** the engine
  1. emits `BookDigest` (per-symbol digests plus the shard digest) on `md-updates` and `exec-reports`,
  2. cancels every resting order, each with a terminal `Cancelled`,
  3. emits a final `ChainCheckpoint`.

  After `EndOfDay`, every new order, cancel and replace is rejected with `MarketClosed`, so late arrivals still get a terminal report. Every run ends with an `EndOfDay`.

### 4.6 Exactly-once processing in the primary

For each batch of input records:

1. Match.
2. `beginTransaction()`.
3. Produce exec reports, market data updates and a `ChainCheckpoint`.
4. `sendOffsetsToTransaction({p: lastOffset + 1}, new ConsumerGroupMetadata("engine-shard-n"))`.
5. `commitTransaction()`.

- The engine uses `assign()`, not `subscribe()`, so no consumer-group rebalance can ever move a partition. Fencing comes from the fixed `transactional.id`.
- A batch is capped by record count and by time. Both caps are benchmark parameters (section 15). There is never one transaction per order.

---

## 5. Primary, standby, fencing and promotion

### 5.1 Producer rules (the safety core)

1. **Every node starts as STANDBY**, including a primary that was just restarted.
2. **Only the current lease holder creates the transactional producer** (`transactional.id = engine-shard-{n}`). A standby never creates it, not even to warm up.
3. **Every time a node gets the producer, it runs the full promotion protocol** (5.3).
4. **On `ProducerFencedException` or `InvalidProducerEpochException`:** close the producer, drop to STANDBY, keep the in-memory state, and re-enter the election. Never call `initTransactions()` again without holding the lease.
5. **On losing the lease** (renewal not confirmed before the local deadline): stop producing at once and drop to STANDBY.

Only lease holders ever call `initTransactions()`, so fencing happens only when a new holder takes over. Two nodes can briefly both believe they are primary, but only the one with the newest producer epoch can commit. Safety never depends on the election being perfect.

### 5.2 Roles

- **Standby:** consumes the `orders` partition with `assign()`, applies every event, buffers its outputs, reads `exec-reports` (`read_committed`) to trim the buffer and compare checkpoints, and takes snapshots.
- **Primary:** as in 4.6.

### 5.3 Promotion protocol

1. Hold the lease.
2. Create the producer and call `initTransactions()`. This raises the producer epoch, fences every older producer and aborts any open transaction.
3. Read the committed offset **C** with a `read_committed` consumer, which only returns stable offsets. C is the **next offset to process**. If you read it with `AdminClient.listConsumerGroupOffsets` instead, set `requireStable(true)`.
4. Let **X** be the last offset this node has applied.
   - **If X ≥ C:** the outputs for offsets **C..X** were computed but never published. Publish them from the buffer in the first transaction, committing offset X+1.
   - **If X < C:** apply offsets X+1..C−1 and discard their outputs, because the old primary published them. Then check the chain value against the last `ChainCheckpoint` at or below C−1, and continue from C.
5. Run as primary.

**Boundary unit tests for step 4:**

| Case | Expected |
|---|---|
| X = C−5 | Apply and discard offsets C−4..C−1 |
| X = C−1 | Nothing to publish, nothing to discard |
| X = C | Publish exactly offset C |
| X = C+5 | Publish offsets C..C+5 |

Each test checks the records published, the committed offset and the chain value.

**Eligibility:** a node stands for election only when its lag behind the partition head is below a threshold (for example 10,000 events). A node coming back from a cold restart catches up first.

**A fenced zombie rejoins cheaply.** The engine is deterministic, so a fenced primary's state is still valid for its offset X. It rejoins as standby with its state and buffer intact, without loading a snapshot.

### 5.4 Output buffer

- Each buffered entry is `(srcOffset, outputs, h)`.
- The standby tracks `publishedUpTo`, the highest `srcOffset` seen in committed `exec-reports`, and trims entries at or below it.
- When the standby applies an offset at or below `publishedUpTo` (it is behind the primary), it discards those outputs immediately instead of buffering them. It still advances its chain.
- At each `ChainCheckpoint`, the standby compares chain values. A mismatch increments `engine_hash_divergence_total` and raises an alert. This is the live primary-versus-standby comparison.
- **The buffer is bounded.** At its cap (for example, the primary is alive but stalled), the standby pauses consuming `orders`. Falling behind is safe, because promotion catches up. Memory stays flat.

### 5.5 Leader election

There is a `LeaderElector` interface with one implementation per environment.

**Postgres lease (all environments):**
```sql
UPDATE lease SET holder = $me, expires_at = now() + $ttl
WHERE shard = $n AND (holder = $me OR expires_at < now());
```
- Expiry is judged by the **database's** clock, never a node's.
- Renew every ttl/3.
- Treat the lease as lost if no renewal succeeds within ttl − margin, timed on the local monotonic clock.

**Kubernetes Lease (stretch):** fabric8 `coordination.k8s.io/Lease`.

**GC:** engines run on ZGC and export GC pause time. A pause longer than the lease causes a real failover. That is what test 2 simulates on purpose; pauses are reported so they don't silently inflate failover numbers.

**A Postgres outage is a safe halt.** Engine leases and gateway account ownership (8.2) both live in Postgres. If it goes down:
- every primary fails to renew and steps down within the lease time,
- gateways stop accepting orders for accounts whose ownership they can't confirm,
- orders already in `orders` wait, and standbys keep applying and buffering.

When Postgres returns, a caught-up node wins, promotes and publishes its buffered outputs. Nothing is lost. This availability limit is documented and tested by chaos scenario 12. Stretch mitigations: a Postgres replica with failover, or the Kubernetes Lease for engines.

---

## 6. Snapshots, retention and recovery

- **Content:** `{formatVersion, shard, offset, h, books, dedup marks, md sequences, halt flags, stateDigest}`.
- **Who takes them:** the standby, every N events or T seconds. It pauses its matching loop, serializes to a byte buffer (milliseconds for tens of symbols), resumes, and uploads asynchronously. MinIO or a local volume in development, S3 on AWS. Keep the last 3.
- **No standby running:** after a promotion, until a new standby is up, the primary takes a snapshot only if none has been written for 2T. The latency spike that causes is visible on the dashboard and documented.
- **Retention:**
  - During a benchmark run, `orders` keeps everything (`retention.ms=-1`); the cluster is destroyed afterwards.
  - For long-running use, retention must exceed twice the snapshot interval, and full replay from offset 0 is not available.
- **Cold restart:** load the newest snapshot whose digest verifies (fall back to the previous one), seek to offset+1, replay to the head while checking chain checkpoints, then join as standby.
- **Measured:**
  - recovery from a snapshot versus full replay from offset 0,
  - failover with a caught-up standby versus a lagging one.

---

## 7. Golden model and engine testing

- The golden model is written from `spec/matching.md`, separately from the real engine, with linear scans and no optimization.
- It implements everything that affects state: matching, duplicate marks, control messages, market data sequences and both digests.
- **Spec scenario tests:** about 50 hand-written cases from the spec, run against both engines. They catch misreadings of the spec that both engines share, which differential testing cannot.
- **Differential property tests (jqwik):** at least 10⁶ events. Generators are biased toward:
  - prices near the touch and orders that cross,
  - partial fills,
  - cancels of filled or unknown orders,
  - replaces that move priority,
  - duplicates and halts.
- **Determinism test** as in 4.2.
- The verifier runs the golden model again after every chaos run.

---

## 8. Gateway and risk

### 8.1 Pre-trade checks (risk-lib)

- Max order quantity
- Max notional
- Price band versus a reference price
- Max open orders
- Max position (position + open exposure on that side)
- `blocked` flag (kill switch)

### 8.2 Keeping in-gateway risk exact

- **Account ownership, one gateway at a time:**
  - Each account has a home gateway (a static table the replayer follows).
  - Exactly one gateway owns an account at any moment, recorded in Postgres as `account_owner(account, gateway, expires_at)`.
  - Each gateway renews all its rows in one statement every ttl/3, using database time.
- **Exposure is counted at send time.** The gateway adds an order's quantity and notional to the account's open exposure the moment it accepts the order, before Kafka or the engine has seen it. Rejects, cancels and IOC remainders release it; fills move it into the position. The owning gateway sees every fill for its accounts on `exec-reports`, so it keeps positions itself.
- **Maximum overshoot:** zero in steady state, because every in-flight order is counted before the next check. The only window is an ownership handover: orders the old gateway accepted in its last moments may not yet be visible to the new owner. The overshoot is bounded by max open orders × max order notional for that account. The README states this bound.
- **Handover when a gateway fails:**
  1. If the home gateway is unreachable, the client logs on to a failover gateway.
  2. That gateway takes ownership only after the old row has expired (a ttl of a few seconds).
  3. It rebuilds the account's position and open orders (risk-service snapshot, then `exec-reports` catch-up), then sends `LogonAck`. The logon waits until then.
  4. The account returns home at its next logon once the home gateway is back.
- **Losing ownership:** a gateway that fails to renew stops accepting orders for those accounts and disconnects their sessions. A partitioned-but-alive gateway therefore can't keep trading an account that has moved. No verifier check depends on ownership.
- **Gateway restart:** before accepting logons, the gateway reads `risk-limits` from the beginning. It then loads positions, open orders and the risk service's offset vector, and reads `exec-reports` from those offsets to the head.

### 8.3 Risk service

- **Limits travel on the compacted `risk-limits` topic.** Gateways read it from the beginning on startup and stay subscribed. No extra protocol is needed.
- **Positions sink:** consumes `exec-reports` (`read_committed`). In one Postgres transaction, it updates positions and open orders and stores `consumer_offsets(partition, offset)`. On restart it seeks to the stored offsets, so nothing is counted twice. These positions are only used when a gateway rebuilds state.
- **Kill switch:**
  1. Set `blocked=true` for the account on `risk-limits`. Every gateway sees it within one poll.
  2. Write `MassCancel(account)` into every shard partition.
- **Control messages:** writes `Halt`, `Resume` and `EndOfDay` into `orders`.

### 8.4 Replay profile versus risk profile

- **Replay profile:** limits never bind, because a rejected Add would make the replayed book drift from ITCH.
- **Risk profile:** tight limits on synthetic order flow. The verifier checks that rejected orders never reached `orders` and that the kill switch left no resting orders for the account.

---

## 9. Market data

### 9.1 Engine output

Order-level events on `md-updates`: Add, Execute, Cancel (partial), Delete, Replace, Trade, Halt/Resume, and `BookDigest` at EndOfDay.

- Each event carries a per-symbol sequence number from engine state.
- Add and Replace carry the engine order ID packed into ITCH's 8-byte order reference (shard in the top 8 bits, offset in the low 56). A feed consumer therefore rebuilds exactly the engine's book and can compute the same per-symbol digest.

### 9.2 MD and query service

- **Books:** kept from `md-updates`. Every T seconds a book snapshot plus offsets is saved to Postgres; on restart the service loads the latest and replays from there.
- **Fills sink:** in one Postgres transaction, insert the fills (unique `fill_id`) and update `consumer_offsets`. Each fill is stored exactly once.
- **Feed:** an ITCH 5.0 subset over TCP with SoupBinTCP-style framing. On joining, and on any sequence gap, a subscriber requests a snapshot of its symbols and resumes from that snapshot's sequence number.
- **Slow subscribers:** each gets a bounded ring buffer. When it fills, the subscriber falls back to a conflated snapshot; if it stays slow, it is disconnected. Memory stays flat.
- **REST:** fills, orders by account, book snapshots.

### 9.3 C++ feed handler integration

- The existing C++ handler subscribes to the feed and rebuilds books order by order.
- At the EndOfDay system event, it computes the per-symbol book digest for every symbol, using the canonical definition ported to C++ (`spec/digest.md`), and writes them out for the verifier.
- It subscribes with a large buffer so it is never conflated. If it is ever disconnected or sees a gap, it recovers through a snapshot like any other subscriber. Snapshots carry the same order IDs, so the digest stays valid.

---

## 10. Replayer: ITCH to client orders

### 10.1 Message mapping

| ITCH message | Becomes |
|---|---|
| A / F Add Order | NewOrder LIMIT (resting) |
| E Order Executed | Synthetic IOC on the opposite side, for the executed shares, at the resting order's price |
| C Executed with Price | Same as E, at the printed price |
| X Order Cancel | CancelReplace reducing quantity (keeps priority) |
| D Order Delete | Cancel |
| U Order Replace | CancelReplace; the new ITCH order reference maps to the new clOrdId |
| P / Q trades, system and admin messages | Skipped; counts logged |

### 10.2 Accounts and sessions

- ITCH order reference → account by deterministic hash. Each account belongs to one session and has a home gateway (8.2).
- A synthetic IOC is assigned to a **different** account from the resting order it targets, so self-trade prevention doesn't fire.
- Accounts start flat. Short positions are allowed in the replay profile.

### 10.3 Divergence handling

The exchange's book will drift from Nasdaq's. Hidden liquidity (P messages) is skipped, and a synthetic IOC can hit a different order at the same price.

- The replayer keeps a map of `ITCH ref → (clOrdId, expected remaining)`.
- A `CancelRejected` for an order already filled or cancelled here counts as a **divergence event**: an expected metric, not a bug.
- The **divergence rate** per symbol is reported.
- **Fill-ratio sanity range:** executed shares per symbol here versus ITCH E+C shares, overall and for 9:31–15:59. Set the expected range after the first full run, then treat leaving that range as a regression.

### 10.4 Sending

- **Open loop.** Every order has an intended send time. There are two modes:
  - ITCH-paced, at 1× or faster (bursty and realistic),
  - constant rate (for throughput sweeps).
- Several replayer instances, each owning its own sessions, so the load generator is never the bottleneck.
- **Sent log:** `(sessionId, clOrdId, intendedTs, actualTs)`.
- **Response log:** `(sessionId, clOrdId, responseType, shard, offset, receivedTs)`, including gateway rejects and `Busy`.
- Reconnects and resends follow 3.2.

---

## 11. Verifier

Runs after every test. Every check must pass.

| # | Check | Method |
|---|---|---|
| 1 | No lost orders | Every clOrdId in the sent log is either in `orders`, or has a gateway reject (`Rejected` or `Busy`) in the response log and is absent from `orders` |
| 2 | Exactly one terminal state | Every order in `orders` ends in exactly one terminal report: filled, cancelled, rejected, IOC remainder cancelled, EndOfDay cancelled or `MarketClosed` |
| 3 | No duplicate fills in Kafka | `exec-reports`, read with `read_committed`, has no repeated `fill_id` |
| 4 | Sinks match Kafka | Postgres fills equal the Kafka fills. Risk-service positions equal positions recomputed from Kafka fills |
| 5 | Full-history hash match | Replay each `orders` partition through the golden model. Every `ChainCheckpoint` in `exec-reports`, including those from a pre-failover primary, equals the golden chain at that offset |
| 6 | Final state match | At the final offset, golden, primary and standby agree on chain value and state digest |
| 7 | Four-way book match | At EndOfDay, every symbol's book digest is equal across golden, primary, standby and the C++ feed handler |
| 8 | Aborted writes hidden; fencing happened | Diff `read_uncommitted` against `read_committed` to find aborted records. None may appear in the committed view, Postgres or any client's response log. In zombie scenarios, require evidence that fencing happened: aborted records from the zombie's epoch, or a fenced error the zombie logged on its first write after waking. Cross-check against the zombie's own log of attempted writes |
| 9 | Client view complete | For every session, the reports the client received (after dropping repeats by `(shard, offset)`) equal that session's reports in Kafka |
| 10 | No live divergence | `engine_hash_divergence_total` is 0 on every standby |

The golden model replays what actually landed in `orders`, not the sent log, because arrival order across sessions is nondeterministic. The sent and response logs only prove that nothing was lost.

---

## 12. Chaos testing

### 12.1 Harness

`chaos/run <scenario> <runs>`. Each run:

1. **Resets** the environment: deletes and recreates topics, truncates Postgres, clears snapshots, restarts services.
2. Starts a fixed ITCH slice, for example 10 minutes of a busy session at 5×.
3. Injects the fault at a random time inside a set window. For scenario 2, half the runs instead use the test hook `chaos.pauseBeforeCommit` (off by default), which holds the primary after it sends a batch and before it commits. Every suite then exercises both fencing paths.
4. Runs to EndOfDay.
5. Runs the verifier.
6. Writes a JSON result: pass/fail per check, failover time, recovery time.

Each scenario is reported as N passes out of N runs, with at least 10 runs.

### 12.2 Scenarios

| # | Fault | Tool | What it proves |
|---|---|---|---|
| 1 | SIGKILL the primary mid-stream; let it restart immediately | `docker kill` / `kubectl delete pod --grace-period=0` | Basic failover, and the restarted node comes back as standby without fencing the new primary |
| 2 | Pause the primary longer than the lease | `docker pause` / SIGSTOP / test hook | The zombie is fenced when it wakes and rejoins as standby. **Headline test** |
| 3 | Cut the primary off from Kafka and Postgres | Toxiproxy / NetworkPolicy | Losing the lease leads to clean fencing |
| 4 | Kill one Kafka broker | `docker kill` / `kubectl delete pod` | RF=3 and min ISR=2 hold up |
| 5 | Rolling restart of all brokers | Compose script / Strimzi rolling update | Transactions survive broker churn |
| 6 | Kill a gateway; the client reconnects to a different gateway | `kill -9` | Duplicate detection, resume and account handover |
| 7 | Kill the Postgres sink mid-transaction | `kill -9` | Offset-in-transaction sink |
| 8 | Kill the standby; cold restart | `kill -9` | Snapshot plus replay rebuilds identical state |
| 9 | Slow market-data subscriber | Throttled client | Conflation, then disconnect; memory stays flat |
| 10 | Pause the standby for 30 s, then kill the primary | `docker pause` + `kill` | Promoting a lagging standby; its failover time is reported separately |
| 11 | Kill the risk service; use the kill switch after it restarts | `kill -9` | Positions aren't double counted; MassCancel goes through the log |
| 12 | Stop Postgres for 30 s | `docker stop` / scale to 0 | Safe halt: nothing is lost, trading resumes, and every check passes |

---

## 13. Backpressure

- **Gateway:**
  - When a session has more than K unacked orders, turn off Netty `autoRead` for it.
  - A bounded queue sits between Netty and the producer.
  - Set `buffer.memory` and a small `max.block.ms`, and answer `Busy` instead of blocking the I/O thread.
- **Engine:** bounded handoff queues, a fixed `max.poll.records`, and the standby's bounded buffer (5.4). Consumer lag is exported as a metric.
- **Market data:** per-subscriber ring buffer → conflated snapshot → disconnect.
- Every queue depth is a Prometheus gauge, so backpressure is visible on Grafana during benchmarks.

---

## 14. Observability

- **Prometheus metrics** from every service:
  - queue depths, consumer lag,
  - transaction commit time, batch sizes,
  - GC pauses,
  - divergence counter, lease state, failovers,
  - snapshot age.
- **Grafana dashboards:** order flow, latency percentiles, per-shard engine state (role, lag, chain checkpoint), backpressure, chaos-run timeline.
- **Stage timestamps** in Kafka headers, never hashed, so a single order's latency can be broken down stage by stage (section 15).

---

## 15. Metrics and benchmarking

| Metric | Definition |
|---|---|
| **L1 latency (headline)** | Client-observed time from intended send to exec report received, measured in the replayer with HdrHistogram. Sending is open loop, so the numbers don't hide queueing delay. Report p50, p99 and p99.9 |
| L2 latency | Internal time from gateway receive to the exec report being written to the client's socket |
| Stage breakdown | Gateway receive → Kafka append → engine poll → match done → commit done → gateway consume → socket write. Cross-node stages assume chrony-synced clocks, which is accurate enough at millisecond scale |
| Throughput | Constant-rate sweep: raise the rate step by step until L1 p99 crosses a threshold. Repeat at 1, 2, 4 and 8 shards for a scaling curve |
| Batch size trade-off | Throughput against L1 p99 at 3 transaction batch caps (for example 16, 128 and 1,024 records, each with a 2 ms time cap). One chart that explains the millisecond latency honestly |
| Burst behaviour | ITCH-paced replay of the market open at 1× and 5×: peak queue depth, and L1 during the bursts |
| Failover time | From the kill or pause to the first new ack on that shard. Measured at leases of 2 s, 5 s and 10 s, with a caught-up standby and with a lagging one |
| Recovery time | Cold restart from a snapshot versus full replay |
| Chaos results | Pass counts per scenario and per verifier check |
| GC | Maximum and p99 pause per service |
| Gateway fan-out cost | CPU spent filtering `exec-reports` per gateway |
| Cost per run | From cost-allocation tags (section 16) |

**Benchmark hygiene:**
- Separate node groups for Kafka, engines, gateways and load generators.
- A shard's primary and standby always on different nodes (anti-affinity).
- Warm-up period excluded from results.
- Every result recorded with its environment: instance types, Kafka version, JVM flags, batch caps.

**Expected ranges:** with `acks=all` and transactions, L1 latency will be milliseconds, and failover time will be close to the lease duration. The README explains both.

---

## 16. Infrastructure and cost

- **Day-to-day:** Docker Compose, then kind. $0.
- **AWS (stretch):** a GitHub Actions workflow:
  1. `terraform apply`
  2. `helm install`
  3. run the benchmark and the chaos suite
  4. upload results
  5. teardown, in order: `helm uninstall`, delete PVCs, wait until no load balancers or EBS volumes tagged with the run id remain, then `terraform destroy`. Every teardown step runs with `if: always()`.
- **State and credentials:** Terraform state in S3 with locking. GitHub Actions assumes an IAM role through OIDC; no static keys.
- **Resources Terraform doesn't track:**
  - No `Service type=LoadBalancer`. Load generators run inside the cluster; Grafana is reached by port-forward.
  - The EBS CSI StorageClass uses `reclaimPolicy: Delete` and tags its volumes.
- **Safety nets:**
  - A nightly workflow deletes anything tagged `project=exchange-sim`, EBS volumes and load balancers included.
  - An AWS Budgets alert, defined in Terraform.
- **Cost tracking:** activate the `run-id` cost-allocation tag in Week 0; activation takes up to 24 h. Cost Explorer lags by about a day, so the workflow also records an immediate estimate from instance-hours.
- **Savings:**
  - Kafka in the cluster via Strimzi rather than MSK.
  - Public subnets with tight security groups, so no NAT Gateway.
  - Spot instances for load generators.
  - Check current regional prices before quoting a cost.

### Repository layout

```
exchange-sim/
├── spec/                matching.md, protocol.md, itch-mapping.md, digest.md
├── protocol/            binary codec, message definitions
├── engine-core/         book, matching, hash chain, digests, snapshot format (no dependencies)
├── golden/              naive reference engine
├── engine-node/         Kafka wiring, LeaderElector, roles, promotion, output buffer, snapshots
├── risk-lib/            in-memory pre-trade checks, exposure tracking
├── gateway/             Netty server, session layer, resume, watermarks, account ownership
├── risk-service/        Spring Boot: limits topic, positions sink, kill switch, control messages
├── md-query-service/    Spring Boot + Postgres: fills sink, books, ITCH-style feed, REST
├── replayer/            ITCH → orders, open-loop sender, resend, sent and response logs
├── verifier/            checks 1–10
├── integration/
│   └── feed-handler/    build script and digest output for the C++ handler
├── chaos/               harness and scenarios (Compose and Kubernetes)
├── bench/               load profiles, HdrHistogram reports, result tables
└── deploy/
    ├── compose/         3 brokers, Postgres, MinIO, Toxiproxy, Prometheus, Grafana
    ├── helm/
    └── terraform/
```

---

# Part B: Workplan

## 17. Timeline at a glance

| Week | Dates | Theme | Exit criterion |
|---|---|---|---|
| 0 | Nov 26 – Dec 17 | Specs and setup | Four spec documents written; ~50 scenario cases defined; repo builds in CI |
| 1 | Dec 18 – 24 | Engine core and golden model | Scenario tests, ≥10⁶ differential events and determinism tests pass |
| 2 | Dec 25 – 31 | End-to-end single node (lighter holiday week) | An ITCH slice for 10 symbols runs end to end on Compose |
| 3 | Jan 1 – 7 | Replication, fencing and failover | Scenarios 1, 2 and 10 pass 10/10 on Compose |
| 4 | Jan 8 – 14 | Recovery, services and the full chaos suite | **MVP:** all 12 scenarios pass 10/10 on Compose; README draft |
| 5 | Jan 15 – 21 | C++ integration, observability, Kubernetes | Four-way digest match; chaos suite green on kind |
| 6 | Jan 22 – 28 | AWS benchmark and write-up | Numbers measured; README and resume bullets final |

## 18. Week-by-week tasks

### Week 0 · Nov 26 – Dec 17 · Specs and setup

The rest of the plan depends on these documents, so this week is required.

- [ ] `spec/matching.md`: order types, collar, priority, cancel-replace rules, tick and lot size, self-trade prevention, halt, EndOfDay, ID formats, control messages
- [ ] About 50 scenario cases in the spec as tables (input events → expected outputs), covering every rule above
- [ ] `spec/protocol.md`: message layouts, session layer, resume vector, watermarks, clOrdId rule, `Busy` retry rule
- [ ] `spec/itch-mapping.md`: mapping table, account and session assignment, synthetic IOC account rule, divergence handling
- [ ] `spec/digest.md`: canonical encodings, per-symbol and shard digests, chain formula
- [ ] Pick 10–50 symbols; download one ITCH day; use your C++ handler to count message types per symbol (sizes the replay)
- [ ] Gradle multi-module repo skeleton; GitHub Actions CI (build + test on every push)
- [ ] Compose skeleton: 3 KRaft brokers, Postgres, MinIO, Prometheus
- [ ] If EKS stays in scope: activate the `run-id` cost tag and create the budget alert

**Exit:** the four specs are written and re-read once with fresh eyes; the scenario tables exist; CI is green on an empty build.

### Week 1 · Dec 18 – 24 · Engine core and golden model

- [ ] Domain model: orders, input events, output records, integer prices
- [ ] Order book: sorted price levels, FIFO queues, order index
- [ ] Matching: LIMIT, MARKET with collar, IOC, cancel, cancel-replace priority rules, self-trade prevention, halt/resume, EndOfDay, `MarketClosed`
- [ ] Duplicate high-water marks per (session, shard); market data sequence numbers
- [ ] Hash chain, per-symbol digest, shard digest, state digest
- [ ] Canonical snapshot write and read, with a format version
- [ ] Golden engine (linear scans), written from the spec rather than from engine code
- [ ] Spec scenario tests run against both engines
- [ ] jqwik differential tests with biased generators; determinism test

**Exit:** all scenario tests pass; differential tests pass on ≥10⁶ events; snapshot → restore gives an identical digest; determinism test passes.

### Week 2 · Dec 25 – 31 · End-to-end single node (lighter holiday week)

- [ ] `protocol` codec with round-trip tests
- [ ] Gateway: Netty server, logon, heartbeats, resume vector, watermarks, single FIFO path per session plus the ordering test (no risk checks yet)
- [ ] engine-node, primary only: `assign()`, batch caps, transactions, `ChainCheckpoint`, telemetry in headers
- [ ] Replayer v1: A/F, D and E mapping; open-loop sending; sent and response logs
- [ ] Compose stack running all of the above

**Exit:** an ITCH slice for 10 symbols plays end to end; first fill ratio and divergence rate recorded.

### Week 3 · Jan 1 – 7 · Replication, fencing and failover

- [ ] risk-lib: checks, exposure counted at send time, positions from fills; gateway reads `risk-limits` (seeded by a script for now)
- [ ] engine-node standby: output buffer, `publishedUpTo`, discard-at-apply, checkpoint comparison, divergence metric, bounded buffer
- [ ] Postgres `LeaderElector` using database time
- [ ] Producer rules (5.1) and promotion protocol (5.3), with the four boundary unit tests
- [ ] Eligibility threshold; fenced zombie rejoining as standby
- [ ] Replayer v2: full mapping (C, X, U), reconnect and resend, `Busy` retry with a new clOrdId
- [ ] Verifier v1: checks 1, 2, 3, 5, 6, 8, 10
- [ ] Chaos harness with reset; `chaos.pauseBeforeCommit` test hook
- [ ] Basic Prometheus metrics (you'll need them to debug failovers)

**Exit:** scenarios 1, 2 and 10 pass 10/10 on Compose.

### Week 4 · Jan 8 – 14 · Recovery, services and the full chaos suite

- [ ] Snapshots: standby snapshotting, primary fallback, upload to MinIO, cold restart with digest check
- [ ] md-query-service: fills sink with offsets in the same transaction, book state with persisted snapshots, ITCH-style feed with snapshots and gap recovery, slow-subscriber handling, REST
- [ ] risk-service: `risk-limits` publisher, positions sink, kill switch, `Halt`/`Resume`/`EndOfDay` writer
- [ ] Gateway: account ownership leases, handover with rebuild, losing ownership, restart rebuild
- [ ] Backpressure: `autoRead`, bounded queues, `Busy`, queue-depth gauges
- [ ] Toxiproxy in Compose
- [ ] Verifier: checks 4 and 9 (everything except 7)
- [ ] Run scenarios 3–9, 11 and 12

**Exit (MVP):** all 12 scenarios pass 10/10 on Compose, and the README draft includes the Compose results. From here on the project is complete and presentable; everything after this week adds to it.

### Week 5 · Jan 15 – 21 · C++ integration, observability, Kubernetes

- [ ] C++ feed handler: subscriber over the exchange feed, port of the per-symbol digest, end-of-day output; verifier check 7
- [ ] Grafana dashboards (section 14)
- [ ] Stage latency breakdown from header timestamps
- [ ] Local benchmarks: batch-size chart, burst replay, recovery times
- [ ] Helm charts; kind cluster with Strimzi; chaos scenarios on kind

**Exit:** four-way digest match; chaos suite green on kind.

### Week 6 · Jan 22 – 28 · AWS benchmark and write-up

- [ ] Terraform for EKS: node groups, remote state with locking, OIDC role, tags, budget alert
- [ ] GitHub Actions benchmark workflow with ordered teardown and the nightly sweeper
- [ ] Benchmark run: throughput sweep, shard scaling, batch-size chart, failover at leases of 2 s, 5 s and 10 s, final chaos runs
- [ ] README: architecture diagram, design decisions, results tables, the overshoot bound, known limits (Postgres halt, static sharding)
- [ ] Fill in the resume bullets with measured numbers

**Exit:** numbers measured, cluster destroyed, cost recorded, README final.

## 19. Risks and cut order

| Risk | Early warning | Response |
|---|---|---|
| Schedule slips | Week 3 exit not met by Jan 9 | Apply the cut order below; protect the Week 4 MVP |
| Replay drifts too far from ITCH | Divergence rate high in Week 2 | Narrow to the most liquid symbols; tune the synthetic IOC mapping; document the rate |
| Transaction latency higher than expected | L1 p99 above about 50 ms locally | Tune batch caps and `linger.ms`; report the trade-off chart rather than chasing the number |
| Laptop can't run kind with Strimzi | kind cluster unstable in Week 5 | Run chaos on Compose only; go straight to a short EKS run, or skip Kubernetes |
| Flaky chaos results | A scenario passes less than 10/10 | Treat it as a bug. Keep the failing run's JSON and logs; never re-run until it passes |

**If you fall behind, cut in this order:**

1. EKS: publish kind (or Compose) results with the hardware stated. This is the biggest saving.
2. The Kubernetes Lease elector: keep the Postgres elector everywhere.
3. Risk-service endpoints beyond static limits and the kill switch.
4. Market-data subscribers other than the C++ feed handler.
5. Repetitions on kind (keep 10/10 on Compose).

**Never cut:** the spec, the golden model, the producer rules and promotion protocol, chain checkpoints, the verifier, and the zombie-pause test.

## 20. Resume bullets

Fill these in only after measuring.

- "Built a sharded exchange in Java (Netty, Kafka) matching [X] orders/s at [Y] ms client-observed p99 across [N] deterministic matching engines, each run as a primary with a hot standby fenced by Kafka transactional producer epochs; failover in [Z] s at a [L] s lease"
- "Proved zero lost orders and zero duplicate fills across [N] randomized chaos runs (killed and paused zombie primaries, network partitions, broker restarts, gateway crashes) with a verifier that checks every primary checkpoint against an independent golden model, plus a four-way book digest match including my C++ ITCH feed handler"
- "Replayed a full Nasdaq ITCH day as client order flow, reproducing [P]% of executed volume for [S] symbols; deployed on Docker and Kubernetes (Strimzi, Prometheus, Grafana) [and on EKS via Terraform and GitHub Actions at $[C] per run]"
