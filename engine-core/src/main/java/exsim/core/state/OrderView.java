package exsim.core.state;

import exsim.core.model.Side;

/** Immutable view of one resting order, used for state digests, snapshots and comparison. */
public record OrderView(String symbol, Side side, long price, long priority, long orderId,
                        long sessionId, long clOrdId, long account, long qty, long filled) {
    public long leaves() {
        return qty - filled;
    }
}
