# Matching rules

**Status:** implemented in `engine-core` (`MatchingEngine`) and `golden` (`GoldenEngine`). Every rule below is checked by `ScenarioTest` and by the differential tests.

## 1. Conventions

- **Prices** are `int64` with 4 implied decimals, as in ITCH: $1.00 = `10_000`. There is no floating point anywhere.
- **Quantities** are `int64` shares.
- **Offsets** are the `orders` partition offsets. They strictly increase and may have gaps (Kafka transaction markers).
- **Shard** is 0–255.
- **Symbols** are 1–8 printable ASCII characters with no spaces.

## 2. Inputs

One `orders` partition, applied in offset order. Nothing else affects state: no wall clock, no randomness.

| Event | Fields | Sent by |
|---|---|---|
| `NewOrder` | sessionId, clOrdId, account, symbol, side, type (LIMIT, MARKET, IOC), qty, price | Client |
| `CancelOrder` | sessionId, clOrdId, origClOrdId | Client |
| `ReplaceOrder` | sessionId, clOrdId, origClOrdId, newQty (new **total** quantity, including filled), newPrice | Client |
| `MassCancel` | scope (ACCOUNT, SESSION, ALL), id | Risk service |
| `Halt`, `Resume` | symbol | Risk service |
| `EndOfDay` | — | Risk service |

`clOrdId` is at least 1 and strictly increases per session. A message that breaks these field rules is malformed and never reaches the engine; the gateway rejects it.

## 3. State

- Per symbol: book (bids and asks), market data sequence number, halt flag.
- Per resting order: orderId, sessionId, current clOrdId, account, side, price, priority, qty, filled.
- Per session: duplicate high-water mark.
- The end-of-day flag.

**Symbol entries** are created when a `NewOrder` passes the duplicate check (even if it is then rejected), or by `Halt`/`Resume`. They are never removed.

## 4. Duplicate detection

For every client message, before anything else: if `clOrdId` ≤ the session's high-water mark, emit `DuplicateIgnored(sessionId, clOrdId)` and stop. Nothing else changes. Otherwise set the mark to `clOrdId` and continue. A message that is then rejected **still advances the mark**, so resending it is a duplicate; the client recovers the original reject through resume.

## 5. Validation order

The first failing check wins.

| Message | Checks, in order | Rejected with |
|---|---|---|
| NewOrder | duplicate → market closed → qty ≤ 0 → price off tick (not MARKET) → symbol halted → MARKET with empty opposite side | `Rejected`: MARKET_CLOSED, INVALID_QTY, INVALID_PRICE, HALTED, NO_LIQUIDITY |
| CancelOrder | duplicate → market closed → no live order with (sessionId, origClOrdId) | `CancelRejected`: MARKET_CLOSED, UNKNOWN_ORDER |
| ReplaceOrder | duplicate → market closed → unknown order → newQty ≤ 0 → newPrice off tick → symbol halted | `CancelRejected`: MARKET_CLOSED, UNKNOWN_ORDER, INVALID_QTY, INVALID_PRICE, HALTED |

Cancels are allowed while a symbol is halted. Only an order's **current** clOrdId identifies it; after a replace, the old clOrdId is unknown.

## 6. Processing and output order

Outputs of one event are emitted in exactly this order. `seq` is the symbol's market data sequence, incremented once for every market data output on that symbol.

### 6.1 NewOrder (accepted)

1. `Accepted` (for MARKET, `price` is the collar limit).
2. Match against the opposite side, best price first, then earliest priority, while the order has quantity and the best opposite price crosses (BUY: resting price ≤ limit; SELL: resting price ≥ limit). For each match:
   1. If the resting order has the **same account**: emit `Cancelled(SELF_TRADE)` for the aggressor's remaining quantity and stop. Nothing rests.
   2. Trade quantity = min of both remaining quantities, at the **resting** price. `matchSeq` counts matches within the event, starting at 1.
   3. `Fill` for the resting order, `Fill` for the aggressor, then `MdExecute` (resting orderId). A resting order whose quantity reaches zero leaves the book; there is no separate delete.
3. If quantity remains: LIMIT → rest on the book with priority = this offset, emit `MdAdd`. IOC or MARKET → `Cancelled(IOC_REMAINDER)`.

### 6.2 CancelOrder

`Cancelled(REQUESTED)` (clOrdId = the order's, requestClOrdId = the cancel's), then `MdDelete`.

### 6.3 ReplaceOrder

Let `newLeaves = newQty − filled`.

| Case | Outputs |
|---|---|
| `newLeaves ≤ 0` | `Cancelled(REPLACED_TO_ZERO)`, `MdDelete` |
| Same price and `newLeaves ≤ leaves` (**keeps priority**) | `Replaced(keptPriority = true)`, then `MdReduce` only if leaves dropped |
| Otherwise (**loses priority**) | `Replaced(keptPriority = false)`, `MdDelete`, then the order matches as an aggressor exactly as in 6.1 step 2 (self-trade prevention included), then `MdAdd` for any remainder with priority = this offset |

The order keeps its orderId in every case, and its clOrdId becomes the replace's clOrdId.

### 6.4 MassCancel

Matching orders are cancelled in **canonical order** (section 8). For each: `Cancelled(MASS_CANCEL)` (requestClOrdId 0), then `MdDelete`.

### 6.5 Halt, Resume

Set or clear the halt flag and emit `MdHalt(halted)`. Repeating a halt is allowed and still emits.

### 6.6 EndOfDay

If already closed: no outputs. Otherwise:
1. `BookDigest`: per-symbol book digests for symbols with resting orders, sorted by symbol, plus the shard digest (`spec/digest.md`).
2. Every resting order in canonical order: `Cancelled(END_OF_DAY)`, `MdDelete`.
3. Mark closed. From then on every client message gets MARKET_CLOSED (after the duplicate check).

## 7. Prices

- **Tick:** $0.01 (`100`) at or above $1.00 (`10_000`); $0.0001 (`1`) below. A price is valid when it is > 0 and a multiple of its tick.
- **Market collar, 5%:**
  - BUY limit = the highest valid price ≤ ⌊bestAsk × 105 / 100⌋.
  - SELL limit = the lowest valid price ≥ ⌈bestBid × 95 / 100⌉.

## 8. Canonical order

Used for mass cancels, end of day, digests and snapshots: symbol (ASCII ascending), then BUY before SELL, then best price first (BUY descending, SELL ascending), then priority ascending.

## 9. Identifiers

- **orderId** = `(shard << 56) | offset of the NewOrder`. The same layout fills the 8-byte ITCH order reference on the market data feed.
- **Fill identity** = `(shard, offset, matchSeq, aggressor flag)`.
- **requestClOrdId** in `Cancelled` is the cancel or replace that caused it, or 0 if the engine did.

## 10. Scenario catalogue

Implemented in `golden/src/test/java/exsim/golden/ScenarioTest.java`. Each runs on both engines and compares outputs, chain and full state after every event.

| # | Scenario | Expected |
|---|---|---|
| s01 | Limit order, empty book | Accepted, MdAdd |
| s02 | Crossing order | Trades at the resting price; passive fill, aggressor fill, MdExecute |
| s03 | Partial fill | Remainder rests with leaves quantity |
| s04 | Sweep | Levels consumed best price first; remainder rests |
| s05 | Time priority | Older order at the same price fills first |
| s06 | IOC partial | Remainder cancelled, nothing rests |
| s07 | IOC no cross | Accepted then cancelled in full |
| s08 | Market order | Stops at the 5% collar; remainder cancelled |
| s09 | Market, empty opposite side | NO_LIQUIDITY |
| s10 | Collar rounding | SELL rounds up to tick; BUY rounds down |
| s11 | Off-tick prices | INVALID_PRICE (including 0 and $1.0001) |
| s12 | Sub-dollar tick | $0.5001 and $0.9999 accepted |
| s13 | Non-positive qty | INVALID_QTY |
| s14 | Cancel | Cancelled + MdDelete; order no longer trades |
| s15 | Cancel unknown | UNKNOWN_ORDER |
| s16 | Cancel after fill | UNKNOWN_ORDER |
| s17 | Replace, reduce qty | Keeps priority; MdReduce |
| s36 | Replace, same qty and price | Keeps priority; no MdReduce |
| s18 | Replace, increase qty | Loses priority; MdDelete + MdAdd |
| s19 | Replace to a crossing price | Trades immediately as aggressor |
| s20 | Replace to ≤ filled | REPLACED_TO_ZERO |
| s21 | Replace changes clOrdId | Old id unknown, new id cancels |
| s22 | Replace while halted | HALTED |
| s23 | Same clOrdId again | DuplicateIgnored; state unchanged |
| s24 | Lower clOrdId | DuplicateIgnored (orders and cancels) |
| s25 | Marks per session | Other sessions unaffected |
| s26 | Rejected message | Still advances the mark |
| s27 | Self-trade | Aggressor cancelled; resting untouched |
| s28 | Self-trade after fills | Fills against others first, then stops |
| s29 | Halt / resume | New orders HALTED (incl. market); cancels allowed; MdHalt both ways |
| s30 | Mass cancel by account | Only that account, canonical order |
| s31 | Mass cancel by session, all | Scoped correctly; empty when nothing rests |
| s32 | End of day | BookDigest first, all cancelled, then MARKET_CLOSED; repeat is a no-op |
| s33 | Market data sequences | Independent per symbol |
| s34 | orderId layout | `(shard << 56) | offset` |
| s35 | Book digest | Same for books that differ only in sessions/accounts; state digest differs |

Planned additions as the gateway and replayer land: scenarios driven by real ITCH sequences (an add, partial executions via synthetic IOCs, then delete).
