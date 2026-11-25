# ITCH to client orders

**Status:** draft, written in Week 0. To be implemented in Week 2–3 (`replayer` module). Input: one Nasdaq TotalView-ITCH 5.0 day file.

## 1. Message mapping

| ITCH message | Becomes |
|---|---|
| `A` / `F` Add Order | NewOrder LIMIT (resting) at the ITCH price and shares |
| `E` Order Executed | Synthetic IOC on the opposite side, for the executed shares, at the resting order's price |
| `C` Order Executed With Price | Same as E, at the execution price printed in the message |
| `X` Order Cancel | CancelReplace reducing the quantity by the cancelled shares (keeps priority) |
| `D` Order Delete | Cancel |
| `U` Order Replace | CancelReplace to the new price and shares; the new ITCH order reference maps to the new clOrdId |
| `P`, `Q` trades; system, admin and other messages | Skipped; counts logged per type |

Why E and C become IOCs: ITCH adds are resting orders that didn't cross. Replaying only adds would build a book that never trades. Executions are what reveal the aggressive orders.

## 2. Accounts and sessions

- **Account** = deterministic hash of the ITCH order reference into N accounts (default 64).
- **Session** = account mod S (default 16). Each account belongs to exactly one session.
- **Home gateway** = session mod G.
- **Synthetic IOC account:** chosen so it differs from the resting order's account, so self-trade prevention doesn't fire: `(restingAccount + 1 + hash(executionMatchNumber) mod (N − 1)) mod N`.

## 3. Bookkeeping

The replayer keeps a map of `ITCH order reference → (session, current clOrdId, expected remaining shares)`. It updates the entry on every X, U, E, C and D, and drops it when the order is fully executed or deleted.

## 4. Divergence (expected, measured)

The replayed book will drift from Nasdaq's: hidden liquidity (P messages) is skipped, and a synthetic IOC can hit a different order at the same price.

- A `CancelRejected(UNKNOWN_ORDER)` for an order this exchange already filled or cancelled counts as a **divergence event**, not an error.
- Report the divergence rate per symbol.
- **Fill-ratio sanity range:** executed shares per symbol here versus ITCH E+C shares, overall and for 9:31–15:59. Set the range after the first full run, then treat leaving it as a regression.

## 5. Sending

- **Open loop.** Each order's intended send time is its ITCH timestamp divided by the speed-up factor. Latency is measured from the intended time, so queueing delay isn't hidden.
- **Modes:** ITCH-paced (1× or faster) and constant rate (throughput sweeps).
- **Sent log:** `(sessionId, clOrdId, intendedTs, actualTs)`.
- **Response log:** `(sessionId, clOrdId, responseType, shard, offset, receivedTs)`.

## 6. Symbol selection (Week 0 task)

Pick 10–50 liquid symbols from the chosen day. Use the existing C++ feed handler to count, per symbol, Add, Execute, Cancel, Delete and Replace messages. That count sizes the replay and sets the initial fill-ratio baseline.
