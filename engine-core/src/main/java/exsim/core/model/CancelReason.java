package exsim.core.model;

/** Why an order left the book without trading. Codes are ordinal + 1. */
public enum CancelReason {
    REQUESTED,
    IOC_REMAINDER,
    SELF_TRADE,
    MASS_CANCEL,
    END_OF_DAY,
    REPLACED_TO_ZERO;

    public int code() {
        return ordinal() + 1;
    }
}
