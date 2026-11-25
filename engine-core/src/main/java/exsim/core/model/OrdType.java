package exsim.core.model;

public enum OrdType {
    /** Rests on the book if not fully filled. */
    LIMIT,
    /** Converted to an IOC at the best opposite price plus or minus the collar. */
    MARKET,
    /** Immediate or cancel: any remainder is cancelled. */
    IOC;

    /** Canonical code: LIMIT = 1, MARKET = 2, IOC = 3. */
    public int code() {
        return ordinal() + 1;
    }
}
