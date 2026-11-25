package exsim.golden;

import exsim.core.model.InputEvent;
import exsim.core.model.InputEvent.CancelOrder;
import exsim.core.model.InputEvent.Halt;
import exsim.core.model.InputEvent.MassCancel;
import exsim.core.model.InputEvent.NewOrder;
import exsim.core.model.InputEvent.ReplaceOrder;
import exsim.core.model.InputEvent.Resume;
import exsim.core.model.OrdType;
import exsim.core.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded random order flow, biased toward the cases that break matching engines: prices
 * near the touch so orders cross, partial fills, cancels of filled or unknown orders,
 * replaces that keep or lose priority, duplicates, self-trades, halts and mass cancels.
 */
final class RandomFlow {
    private static final String[] SYMBOLS = {"AAA", "BBB", "PNY"};
    private static final long[] MID = {1_000_000, 250_000, 5_000}; // $100, $25, $0.50
    private static final int SESSIONS = 4;
    private static final int ACCOUNTS = 6;
    private static final int REMEMBERED = 40;

    private record Issued(long clOrdId, int symbol, long price, long qty) {
    }

    private final Random rnd;
    private final long[] nextClOrdId = new long[SESSIONS + 1];
    private final List<List<Issued>> issued = new ArrayList<>();

    RandomFlow(long seed) {
        rnd = new Random(seed);
        for (int i = 0; i <= SESSIONS; i++) {
            issued.add(new ArrayList<>());
        }
    }

    InputEvent next() {
        int roll = rnd.nextInt(1000);
        int session = 1 + rnd.nextInt(SESSIONS);
        // Halt 1 in 1000, resume 6 in 1000: each symbol is halted about 14% of the time.
        if (roll < 1) {
            return new Halt(SYMBOLS[rnd.nextInt(SYMBOLS.length)]);
        }
        if (roll < 7) {
            return new Resume(SYMBOLS[rnd.nextInt(SYMBOLS.length)]);
        }
        if (roll < 12) {
            return massCancel();
        }
        if (roll < 50) {
            return duplicate(session);
        }
        if (roll < 200) {
            return cancel(session);
        }
        if (roll < 330) {
            return replace(session);
        }
        return newOrder(session);
    }

    private long tick(int symbol) {
        return MID[symbol] >= 10_000 ? 100 : 1;
    }

    private long priceNear(int symbol) {
        long t = tick(symbol);
        long p = MID[symbol] + Math.round(rnd.nextGaussian() * 4) * t;
        double r = rnd.nextDouble();
        if (r < 0.01) {
            return 0;
        }
        if (r < 0.02 && t > 1) {
            return p + t / 2; // off tick
        }
        return p;
    }

    private long quantity() {
        double r = rnd.nextDouble();
        if (r < 0.02) {
            return rnd.nextBoolean() ? 0 : -1;
        }
        if (r < 0.70) {
            return 1 + rnd.nextInt(100);
        }
        if (r < 0.97) {
            return 100 + rnd.nextInt(900);
        }
        return 1000 + rnd.nextInt(4000);
    }

    private void remember(int session, Issued i) {
        List<Issued> list = issued.get(session);
        list.add(i);
        if (list.size() > REMEMBERED) {
            list.remove(0);
        }
    }

    private InputEvent newOrder(int session) {
        int symbol = rnd.nextInt(SYMBOLS.length);
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        double t = rnd.nextDouble();
        OrdType type = t < 0.75 ? OrdType.LIMIT : t < 0.88 ? OrdType.IOC : OrdType.MARKET;
        long price = type == OrdType.MARKET ? 0 : priceNear(symbol);
        long qty = quantity();
        long cl = ++nextClOrdId[session];
        remember(session, new Issued(cl, symbol, price, qty));
        return new NewOrder(session, cl, 1 + rnd.nextInt(ACCOUNTS), SYMBOLS[symbol], side, type, qty, price);
    }

    private InputEvent duplicate(int session) {
        if (nextClOrdId[session] == 0) {
            return newOrder(session);
        }
        long cl = 1 + (long) (rnd.nextDouble() * nextClOrdId[session]);
        int symbol = rnd.nextInt(SYMBOLS.length);
        return new NewOrder(session, Math.min(cl, nextClOrdId[session]), 1, SYMBOLS[symbol], Side.BUY,
                OrdType.LIMIT, 10, MID[symbol]);
    }

    private InputEvent cancel(int session) {
        List<Issued> list = issued.get(session);
        long orig = list.isEmpty() || rnd.nextDouble() < 0.2
                ? nextClOrdId[session] + 1000
                : list.get(rnd.nextInt(list.size())).clOrdId();
        return new CancelOrder(session, ++nextClOrdId[session], orig);
    }

    private InputEvent replace(int session) {
        List<Issued> list = issued.get(session);
        if (list.isEmpty()) {
            return newOrder(session);
        }
        Issued e = list.get(rnd.nextInt(list.size()));
        long baseQty = Math.max(1, e.qty());
        long basePrice = e.price() > 0 ? e.price() : MID[e.symbol()];
        long qty;
        long price;
        double v = rnd.nextDouble();
        if (v < 0.35) {
            qty = Math.max(1, baseQty - rnd.nextInt((int) Math.min(baseQty, 1000)));
            price = basePrice;
        } else if (v < 0.55) {
            qty = baseQty + 1 + rnd.nextInt(200);
            price = basePrice;
        } else if (v < 0.85) {
            qty = baseQty;
            price = priceNear(e.symbol());
        } else if (v < 0.93) {
            qty = 1 + rnd.nextInt(5);
            price = basePrice;
        } else {
            qty = rnd.nextBoolean() ? 0 : baseQty;
            price = rnd.nextBoolean() ? basePrice : -100;
        }
        long cl = ++nextClOrdId[session];
        remember(session, new Issued(cl, e.symbol(), price, qty));
        return new ReplaceOrder(session, cl, e.clOrdId(), qty, price);
    }

    private InputEvent massCancel() {
        return switch (rnd.nextInt(3)) {
            case 0 -> new MassCancel(MassCancel.Scope.ACCOUNT, 1 + rnd.nextInt(ACCOUNTS));
            case 1 -> new MassCancel(MassCancel.Scope.SESSION, 1 + rnd.nextInt(SESSIONS));
            default -> new MassCancel(MassCancel.Scope.ALL, 0);
        };
    }
}
