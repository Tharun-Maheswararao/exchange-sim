package exsim.core;

/**
 * Price rules. Prices are integers with 4 implied decimals, as in ITCH ($1.00 = 10_000).
 * Tick size is $0.01 at or above $1.00 and $0.0001 below.
 */
final class Prices {
    static final long ONE_DOLLAR = 10_000;
    static final int COLLAR_PERCENT = 5;

    private Prices() {
    }

    static long tick(long price) {
        return price >= ONE_DOLLAR ? 100 : 1;
    }

    static boolean onTick(long price) {
        return price > 0 && price % tick(price) == 0;
    }

    /** Highest on-tick price at or below bestAsk * 1.05. */
    static long buyCollar(long bestAsk) {
        long c = Math.multiplyExact(bestAsk, 100 + COLLAR_PERCENT) / 100;
        return c - c % tick(c);
    }

    /** Lowest on-tick price at or above bestBid * 0.95. */
    static long sellCollar(long bestBid) {
        long c = Math.ceilDiv(Math.multiplyExact(bestBid, 100 - COLLAR_PERCENT), 100);
        long t = tick(c);
        long r = c % t;
        return r == 0 ? c : c + (t - r);
    }
}
