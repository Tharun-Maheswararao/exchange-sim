# Canonical encodings, hash chain and digests

**Status:** implemented in `engine-core` (`exsim.core.codec`, `exsim.core.hash`, `exsim.core.state`). The C++ feed handler must port section 4 byte for byte.

## 1. Primitives

All integers are **little-endian**.

| Type | Bytes | Notes |
|---|---|---|
| `u8` | 1 | Enum codes, tags |
| `bool` | 1 | 0 or 1 |
| `i32` | 4 | Counts, matchSeq |
| `i64` | 8 | Ids, prices, quantities, offsets |
| `sym` | 8 | ASCII, right-padded with spaces (as in ITCH) |

**Enum codes** are ordinal + 1:
- **Side:** BUY 1, SELL 2
- **OrdType:** LIMIT 1, MARKET 2, IOC 3
- **Reject:** INVALID_QTY 1, INVALID_PRICE 2, HALTED 3, NO_LIQUIDITY 4, MARKET_CLOSED 5, UNKNOWN_ORDER 6
- **CancelReason:** REQUESTED 1, IOC_REMAINDER 2, SELF_TRADE 3, MASS_CANCEL 4, END_OF_DAY 5, REPLACED_TO_ZERO 6

**Hash:** XXH64 with seed 0.

## 2. Output records

Each record starts with a `u8` tag. Only business fields are encoded; telemetry (timestamps) never enters these bytes.

| Tag | Record | Fields after the tag |
|---|---|---|
| 1 | Accepted | i64 sessionId, i64 clOrdId, i64 orderId, sym symbol, u8 side, u8 type, i64 qty, i64 price |
| 2 | Rejected | i64 sessionId, i64 clOrdId, u8 reason |
| 3 | Fill | i64 sessionId, i64 clOrdId, i64 orderId, sym symbol, u8 side, i64 price, i64 qty, i64 leavesQty, bool aggressor, i32 matchSeq |
| 4 | Cancelled | i64 sessionId, i64 clOrdId, i64 requestClOrdId, i64 orderId, i64 cancelledQty, u8 reason |
| 5 | Replaced | i64 sessionId, i64 clOrdId, i64 origClOrdId, i64 orderId, i64 qty, i64 price, i64 leavesQty, bool keptPriority |
| 6 | CancelRejected | i64 sessionId, i64 clOrdId, i64 origClOrdId, u8 reason |
| 7 | DuplicateIgnored | i64 sessionId, i64 clOrdId |
| 20 | MdAdd | sym symbol, i64 seq, i64 orderId, u8 side, i64 price, i64 qty |
| 21 | MdExecute | sym symbol, i64 seq, i64 orderId, i64 price, i64 qty, i32 matchSeq |
| 22 | MdReduce | sym symbol, i64 seq, i64 orderId, i64 newQty |
| 23 | MdDelete | sym symbol, i64 seq, i64 orderId |
| 24 | MdHalt | sym symbol, i64 seq, bool halted |
| 30 | BookDigest | i32 n, n × (sym symbol, i64 digest), i64 shardDigest |

## 3. Output hash chain

```
h_{-1} = 0
h_n    = XXH64( i64 h_{n-1} ‖ i64 offset_n ‖ encode(output_1) ‖ … ‖ encode(output_k) )
```

The chain advances after **every** input event, including events with no outputs (`k = 0`). Two replicas with equal `h_n` produced identical outputs for identical offsets.

## 4. Book digests (feed-visible)

These use only fields a market data consumer can see, so the C++ feed handler can compute them.

**Per symbol:**
```
digest(symbol) = XXH64( sym symbol ‖ for each resting order in canonical order:
                        u8 side ‖ i64 price ‖ i64 orderId ‖ i64 leavesQty )
```
Canonical order (from `spec/matching.md`, section 8): BUY before SELL, best price first, then priority. On the feed, priority is arrival order at a price level, and an order that loses priority is deleted and re-added.

**Shard:**
```
shardDigest = XXH64( i32 n ‖ for each symbol with resting orders, sorted by symbol:
                     sym symbol ‖ i64 digest(symbol) )
```

Symbols with no resting orders are omitted from both. When comparing, treat a missing symbol as "no resting orders".

## 5. State encoding and state digest

Encodes the complete replicated state; `stateDigest = XXH64(state bytes)`.

```
i32 nSymbols,  nSymbols × (sym symbol, i64 mdSeq, bool halted)            sorted by symbol
i32 nOrders,   nOrders  × (sym symbol, u8 side, i64 price, i64 priority,
                           i64 orderId, i64 sessionId, i64 clOrdId,
                           i64 account, i64 qty, i64 filled)               canonical order
i32 nDedup,    nDedup   × (i64 sessionId, i64 highWater)                   sorted by sessionId
bool endOfDay
```

## 6. Snapshot format (version 1)

```
i64 magic = "EXSNAPS1" (ASCII, read as a little-endian i64)
i32 formatVersion = 1
i32 shard
i64 lastOffset
i64 chainValue
i32 stateLength
stateLength bytes: state encoding (section 5)
i64 stateDigest
```

On restore:
1. Check the magic and the version.
2. Recompute the digest over the state bytes and refuse on mismatch.
3. Rebuild the books by appending orders in canonical order. This restores queue priority exactly.

Snapshots are canonical: `snapshot(restore(s)) == s` byte for byte.
