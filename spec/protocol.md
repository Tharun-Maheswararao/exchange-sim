# Order entry protocol

**Status:** draft, written in Week 0. To be implemented in Week 2 (`protocol` and `gateway` modules).

## 1. Framing

TCP, one connection per session. Every message is:

```
u16 length   (bytes after this field, little-endian)
u8  type
... body (fixed layout per type, little-endian)
```

Prices are `i64` with 4 implied decimals. Symbols are 8 ASCII bytes, right-padded with spaces.

## 2. Inbound messages (client → gateway)

| Type | Message | Body |
|---|---|---|
| `L` | Logon | i64 sessionId, 16 bytes credentials, u8 nShards, nShards × i64 lastOffset (resume vector) |
| `H` | Heartbeat | — |
| `O` | Logout | — |
| `N` | NewOrder | i64 clOrdId, sym symbol, u8 side, u8 type, i64 qty, i64 price |
| `X` | Cancel | i64 clOrdId, i64 origClOrdId |
| `R` | CancelReplace | i64 clOrdId, i64 origClOrdId, i64 newQty, i64 newPrice |

The gateway fills in the account from the session and the receive timestamp; neither is client-controlled.

## 3. Outbound messages (gateway → client)

| Type | Message | Body |
|---|---|---|
| `l` | LogonAck | u8 nShards |
| `h` | Heartbeat | — |
| `a` | Accepted | u8 shard, i64 offset, then the fields of the engine record |
| `f` | Fill | u8 shard, i64 offset, engine record fields |
| `c` | Cancelled | u8 shard, i64 offset, engine record fields |
| `p` | Replaced | u8 shard, i64 offset, engine record fields |
| `j` | Rejected | u8 shard, i64 offset, engine record fields |
| `k` | CancelRejected | u8 shard, i64 offset, engine record fields |
| `d` | DuplicateIgnored | u8 shard, i64 offset, i64 clOrdId |
| `b` | Busy | i64 clOrdId (from the gateway; never reached the log) |
| `w` | Watermark | u8 shard, i64 offset |

Engine record fields follow `spec/digest.md`, section 2.

## 4. Session rules

1. **clOrdId** is a `u64` that strictly increases per session, starting at 1. Gaps are allowed. Cancels and replaces use fresh clOrdIds.
2. **Resume vector:** the client keeps, per shard, the highest offset it has received from engine reports or watermarks. `Logon` carries that vector. The gateway replays that session's reports after each offset, then goes live. The client drops any report whose `(shard, offset)` is at or below what it has already seen.
3. **Watermarks:** about once a second, the gateway sends `Watermark(shard, offset)` for every shard: the highest offset it has fully processed. It is always sent after that session's reports up to that offset.
4. **First logon:** an empty vector (all zeros) starts every shard at the current end of the partition.
5. **Resend after reconnect:** the client resends every order without a response, in original order, with the same clOrdId. Copies that already reached the log come back as `DuplicateIgnored`, and their real outcome arrives through the resume replay.
6. **Busy:** the order never reached the log. Retry with a **new** clOrdId and record the link.
7. **Heartbeats:** both sides send one after 1 s of silence. The connection is dropped after 3 s without any inbound bytes.
