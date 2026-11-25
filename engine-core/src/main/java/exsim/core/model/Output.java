package exsim.core.model;

import exsim.core.codec.Canonical;
import java.util.List;

/**
 * Everything the engine emits for one input event: exec reports (to clients) and
 * market data (to the feed). Only business fields live here, so they can be hashed;
 * telemetry travels in Kafka headers. Encodings are listed in {@code spec/digest.md}.
 */
public sealed interface Output {

    void encode(Canonical w);

    // ---- Exec reports -------------------------------------------------------------

    record Accepted(long sessionId, long clOrdId, long orderId, String symbol, Side side,
                    OrdType type, long qty, long price) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(1).i64(sessionId).i64(clOrdId).i64(orderId).symbol(symbol)
                    .u8(side.code()).u8(type.code()).i64(qty).i64(price);
        }
    }

    record Rejected(long sessionId, long clOrdId, Reject reason) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(2).i64(sessionId).i64(clOrdId).u8(reason.code());
        }
    }

    /** One side of an execution. Both sides of a match share {@code matchSeq}. */
    record Fill(long sessionId, long clOrdId, long orderId, String symbol, Side side, long price,
                long qty, long leavesQty, boolean aggressor, int matchSeq) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(3).i64(sessionId).i64(clOrdId).i64(orderId).symbol(symbol).u8(side.code())
                    .i64(price).i64(qty).i64(leavesQty).bool(aggressor).i32(matchSeq);
        }
    }

    /**
     * Order left the book. {@code clOrdId} identifies the order; {@code requestClOrdId}
     * is the cancel or replace request that caused it, or 0 if the engine did.
     */
    record Cancelled(long sessionId, long clOrdId, long requestClOrdId, long orderId,
                     long cancelledQty, CancelReason reason) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(4).i64(sessionId).i64(clOrdId).i64(requestClOrdId).i64(orderId)
                    .i64(cancelledQty).u8(reason.code());
        }
    }

    record Replaced(long sessionId, long clOrdId, long origClOrdId, long orderId, long qty,
                    long price, long leavesQty, boolean keptPriority) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(5).i64(sessionId).i64(clOrdId).i64(origClOrdId).i64(orderId).i64(qty)
                    .i64(price).i64(leavesQty).bool(keptPriority);
        }
    }

    record CancelRejected(long sessionId, long clOrdId, long origClOrdId, Reject reason) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(6).i64(sessionId).i64(clOrdId).i64(origClOrdId).u8(reason.code());
        }
    }

    record DuplicateIgnored(long sessionId, long clOrdId) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(7).i64(sessionId).i64(clOrdId);
        }
    }

    // ---- Market data --------------------------------------------------------------

    record MdAdd(String symbol, long seq, long orderId, Side side, long price, long qty) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(20).symbol(symbol).i64(seq).i64(orderId).u8(side.code()).i64(price).i64(qty);
        }
    }

    /** Resting order executed; it leaves the book when its quantity reaches zero. */
    record MdExecute(String symbol, long seq, long orderId, long price, long qty, int matchSeq) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(21).symbol(symbol).i64(seq).i64(orderId).i64(price).i64(qty).i32(matchSeq);
        }
    }

    /** In-place quantity reduction that keeps priority; {@code newQty} is the new leaves quantity. */
    record MdReduce(String symbol, long seq, long orderId, long newQty) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(22).symbol(symbol).i64(seq).i64(orderId).i64(newQty);
        }
    }

    record MdDelete(String symbol, long seq, long orderId) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(23).symbol(symbol).i64(seq).i64(orderId);
        }
    }

    record MdHalt(String symbol, long seq, boolean halted) implements Output {
        @Override
        public void encode(Canonical w) {
            w.u8(24).symbol(symbol).i64(seq).bool(halted);
        }
    }

    /** Emitted first at EndOfDay: per-symbol book digests and the shard book digest. */
    record BookDigest(List<SymbolDigest> symbols, long shardDigest) implements Output {
        public BookDigest {
            symbols = List.copyOf(symbols);
        }

        @Override
        public void encode(Canonical w) {
            w.u8(30).i32(symbols.size());
            for (SymbolDigest d : symbols) {
                w.symbol(d.symbol()).i64(d.digest());
            }
            w.i64(shardDigest);
        }
    }
}
