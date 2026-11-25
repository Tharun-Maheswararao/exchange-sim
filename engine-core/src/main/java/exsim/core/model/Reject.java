package exsim.core.model;

/** Reasons for {@link Output.Rejected} and {@link Output.CancelRejected}. Codes are ordinal + 1. */
public enum Reject {
    INVALID_QTY,
    INVALID_PRICE,
    HALTED,
    NO_LIQUIDITY,
    MARKET_CLOSED,
    UNKNOWN_ORDER;

    public int code() {
        return ordinal() + 1;
    }
}
