package exsim.core.model;

public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /** Canonical code: BUY = 1, SELL = 2. */
    public int code() {
        return ordinal() + 1;
    }

    public static Side fromCode(int code) {
        return switch (code) {
            case 1 -> BUY;
            case 2 -> SELL;
            default -> throw new IllegalArgumentException("bad side code " + code);
        };
    }
}
