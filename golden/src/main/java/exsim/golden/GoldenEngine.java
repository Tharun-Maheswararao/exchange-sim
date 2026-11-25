package exsim.golden;

import exsim.core.Engine;
import exsim.core.codec.Canonical;
import exsim.core.hash.HashChain;
import exsim.core.model.CancelReason;
import exsim.core.model.InputEvent;
import exsim.core.model.InputEvent.CancelOrder;
import exsim.core.model.InputEvent.EndOfDay;
import exsim.core.model.InputEvent.Halt;
import exsim.core.model.InputEvent.MassCancel;
import exsim.core.model.InputEvent.NewOrder;
import exsim.core.model.InputEvent.ReplaceOrder;
import exsim.core.model.InputEvent.Resume;
import exsim.core.model.OrdType;
import exsim.core.model.Output;
import exsim.core.model.Output.Accepted;
import exsim.core.model.Output.BookDigest;
import exsim.core.model.Output.CancelRejected;
import exsim.core.model.Output.Cancelled;
import exsim.core.model.Output.DuplicateIgnored;
import exsim.core.model.Output.Fill;
import exsim.core.model.Output.MdAdd;
import exsim.core.model.Output.MdDelete;
import exsim.core.model.Output.MdExecute;
import exsim.core.model.Output.MdHalt;
import exsim.core.model.Output.MdReduce;
import exsim.core.model.Output.Rejected;
import exsim.core.model.Output.Replaced;
import exsim.core.model.Reject;
import exsim.core.model.Side;
import exsim.core.model.SymbolDigest;
import exsim.core.state.BookDigests;
import exsim.core.state.DedupEntry;
import exsim.core.state.OrderView;
import exsim.core.state.StateView;
import exsim.core.state.SymbolView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reference engine, written from {@code spec/matching.md} with flat lists and linear
 * scans. It shares no matching logic with {@code MatchingEngine}: only the data types
 * and the canonical encoders are common. Slow on purpose; clarity over speed.
 */
public final class GoldenEngine implements Engine {

    private static final class G {
        long orderId;
        long session;
        long clOrdId;
        long account;
        String symbol;
        Side side;
        long price;
        long priority;
        long qty;
        long filled;

        long leaves() {
            return qty - filled;
        }
    }

    private static final class Sym {
        final String name;
        long seq;
        boolean halted;

        Sym(String name) {
            this.name = name;
        }
    }

    /** Spec order: symbol, BUY before SELL, best price first, then earliest priority. */
    private static final Comparator<G> SPEC_ORDER = (a, b) -> {
        int c = a.symbol.compareTo(b.symbol);
        if (c != 0) {
            return c;
        }
        if (a.side != b.side) {
            return a.side == Side.BUY ? -1 : 1;
        }
        if (a.price != b.price) {
            boolean aBetter = a.side == Side.BUY ? a.price > b.price : a.price < b.price;
            return aBetter ? -1 : 1;
        }
        return Long.compare(a.priority, b.priority);
    };

    private final int shard;
    private final List<G> resting = new ArrayList<>();
    private final List<Sym> symbols = new ArrayList<>();
    private final List<long[]> highWater = new ArrayList<>();
    private boolean closed;
    private long last = -1;
    private long chain = HashChain.INITIAL;
    private final Canonical scratch = new Canonical(4096);

    private long offset;
    private int matches;
    private List<Output> out;

    public GoldenEngine(int shard) {
        this.shard = shard;
    }

    @Override
    public long chainValue() {
        return chain;
    }

    @Override
    public long lastOffset() {
        return last;
    }

    @Override
    public List<Output> apply(long offset, InputEvent event) {
        if (offset <= last) {
            throw new IllegalArgumentException("offsets must increase");
        }
        this.offset = offset;
        this.matches = 0;
        this.out = new ArrayList<>();
        if (event instanceof NewOrder o) {
            newOrder(o);
        } else if (event instanceof CancelOrder c) {
            cancel(c);
        } else if (event instanceof ReplaceOrder r) {
            replace(r);
        } else if (event instanceof MassCancel m) {
            cancelWhere(m.scope(), m.id(), CancelReason.MASS_CANCEL);
        } else if (event instanceof Halt h) {
            halt(h.symbol(), true);
        } else if (event instanceof Resume r) {
            halt(r.symbol(), false);
        } else if (event instanceof EndOfDay) {
            endOfDay();
        } else {
            throw new IllegalArgumentException("unknown event " + event);
        }
        List<Output> result = List.copyOf(out);
        chain = HashChain.next(chain, offset, result, scratch);
        last = offset;
        return result;
    }

    // ---- Spec rules ----------------------------------------------------------------

    private static boolean validPrice(long p) {
        return p > 0 && (p < 10_000 || p % 100 == 0);
    }

    private static long collar(Side side, long touch) {
        if (side == Side.BUY) {
            long c = touch * 105 / 100;
            while (!validPrice(c)) {
                c--;
            }
            return c;
        }
        long c = (touch * 95 + 99) / 100;
        while (!validPrice(c)) {
            c++;
        }
        return c;
    }

    private static Side other(Side s) {
        return s == Side.BUY ? Side.SELL : Side.BUY;
    }

    private boolean seen(long session, long clOrdId) {
        for (long[] e : highWater) {
            if (e[0] == session) {
                if (clOrdId <= e[1]) {
                    out.add(new DuplicateIgnored(session, clOrdId));
                    return true;
                }
                e[1] = clOrdId;
                return false;
            }
        }
        highWater.add(new long[] {session, clOrdId});
        return false;
    }

    private Sym sym(String name) {
        for (Sym s : symbols) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        Sym s = new Sym(name);
        symbols.add(s);
        return s;
    }

    private G best(String symbol, Side side) {
        G best = null;
        for (G g : resting) {
            if (g.symbol.equals(symbol) && g.side == side && (best == null || SPEC_ORDER.compare(g, best) < 0)) {
                best = g;
            }
        }
        return best;
    }

    private G find(long session, long clOrdId) {
        for (G g : resting) {
            if (g.session == session && g.clOrdId == clOrdId) {
                return g;
            }
        }
        return null;
    }

    private void post(G g) {
        resting.add(g);
        out.add(new MdAdd(g.symbol, ++sym(g.symbol).seq, g.orderId, g.side, g.price, g.leaves()));
    }

    // ---- Events ----------------------------------------------------------------------

    private void newOrder(NewOrder o) {
        if (seen(o.sessionId(), o.clOrdId())) {
            return;
        }
        Sym s = sym(o.symbol());
        long limit = o.price();
        Reject why = null;
        if (closed) {
            why = Reject.MARKET_CLOSED;
        } else if (o.qty() < 1) {
            why = Reject.INVALID_QTY;
        } else if (o.type() != OrdType.MARKET && !validPrice(o.price())) {
            why = Reject.INVALID_PRICE;
        } else if (s.halted) {
            why = Reject.HALTED;
        } else if (o.type() == OrdType.MARKET) {
            G top = best(o.symbol(), other(o.side()));
            if (top == null) {
                why = Reject.NO_LIQUIDITY;
            } else {
                limit = collar(o.side(), top.price);
            }
        }
        if (why != null) {
            out.add(new Rejected(o.sessionId(), o.clOrdId(), why));
            return;
        }
        G g = new G();
        g.orderId = ((long) shard << 56) | offset;
        g.session = o.sessionId();
        g.clOrdId = o.clOrdId();
        g.account = o.account();
        g.symbol = o.symbol();
        g.side = o.side();
        g.price = limit;
        g.priority = offset;
        g.qty = o.qty();
        out.add(new Accepted(g.session, g.clOrdId, g.orderId, g.symbol, g.side, o.type(), g.qty, g.price));
        if (trade(g) || g.leaves() == 0) {
            return;
        }
        if (o.type() == OrdType.LIMIT) {
            post(g);
        } else {
            out.add(new Cancelled(g.session, g.clOrdId, 0, g.orderId, g.leaves(), CancelReason.IOC_REMAINDER));
        }
    }

    /** Returns true if the aggressor was cancelled by self-trade prevention. */
    private boolean trade(G a) {
        while (a.leaves() > 0) {
            G r = best(a.symbol, other(a.side));
            if (r == null) {
                return false;
            }
            boolean crosses = a.side == Side.BUY ? r.price <= a.price : r.price >= a.price;
            if (!crosses) {
                return false;
            }
            if (r.account == a.account) {
                out.add(new Cancelled(a.session, a.clOrdId, 0, a.orderId, a.leaves(), CancelReason.SELF_TRADE));
                return true;
            }
            long q = Math.min(a.leaves(), r.leaves());
            matches++;
            r.filled += q;
            a.filled += q;
            out.add(new Fill(r.session, r.clOrdId, r.orderId, r.symbol, r.side, r.price, q, r.leaves(), false, matches));
            out.add(new Fill(a.session, a.clOrdId, a.orderId, a.symbol, a.side, r.price, q, a.leaves(), true, matches));
            out.add(new MdExecute(r.symbol, ++sym(r.symbol).seq, r.orderId, r.price, q, matches));
            if (r.leaves() == 0) {
                resting.remove(r);
            }
        }
        return false;
    }

    private void cancel(CancelOrder c) {
        if (seen(c.sessionId(), c.clOrdId())) {
            return;
        }
        G g = closed ? null : find(c.sessionId(), c.origClOrdId());
        if (g == null) {
            Reject why = closed ? Reject.MARKET_CLOSED : Reject.UNKNOWN_ORDER;
            out.add(new CancelRejected(c.sessionId(), c.clOrdId(), c.origClOrdId(), why));
            return;
        }
        resting.remove(g);
        out.add(new Cancelled(g.session, g.clOrdId, c.clOrdId(), g.orderId, g.leaves(), CancelReason.REQUESTED));
        out.add(new MdDelete(g.symbol, ++sym(g.symbol).seq, g.orderId));
    }

    private void replace(ReplaceOrder r) {
        if (seen(r.sessionId(), r.clOrdId())) {
            return;
        }
        G g = closed ? null : find(r.sessionId(), r.origClOrdId());
        Reject why = null;
        if (closed) {
            why = Reject.MARKET_CLOSED;
        } else if (g == null) {
            why = Reject.UNKNOWN_ORDER;
        } else if (r.newQty() < 1) {
            why = Reject.INVALID_QTY;
        } else if (!validPrice(r.newPrice())) {
            why = Reject.INVALID_PRICE;
        } else if (sym(g.symbol).halted) {
            why = Reject.HALTED;
        }
        if (why != null) {
            out.add(new CancelRejected(r.sessionId(), r.clOrdId(), r.origClOrdId(), why));
            return;
        }
        Sym s = sym(g.symbol);
        if (r.newQty() <= g.filled) {
            resting.remove(g);
            out.add(new Cancelled(g.session, g.clOrdId, r.clOrdId(), g.orderId, g.leaves(), CancelReason.REPLACED_TO_ZERO));
            out.add(new MdDelete(g.symbol, ++s.seq, g.orderId));
            return;
        }
        long before = g.leaves();
        boolean keep = r.newPrice() == g.price && r.newQty() - g.filled <= before;
        g.clOrdId = r.clOrdId();
        g.qty = r.newQty();
        if (keep) {
            out.add(new Replaced(g.session, g.clOrdId, r.origClOrdId(), g.orderId, g.qty, g.price, g.leaves(), true));
            if (g.leaves() < before) {
                out.add(new MdReduce(g.symbol, ++s.seq, g.orderId, g.leaves()));
            }
            return;
        }
        resting.remove(g);
        g.price = r.newPrice();
        g.priority = offset;
        out.add(new Replaced(g.session, g.clOrdId, r.origClOrdId(), g.orderId, g.qty, g.price, g.leaves(), false));
        out.add(new MdDelete(g.symbol, ++s.seq, g.orderId));
        if (!trade(g) && g.leaves() > 0) {
            post(g);
        }
    }

    private void cancelWhere(MassCancel.Scope scope, long id, CancelReason reason) {
        List<G> hits = new ArrayList<>();
        for (G g : resting) {
            if (scope == MassCancel.Scope.ALL
                    || (scope == MassCancel.Scope.ACCOUNT && g.account == id)
                    || (scope == MassCancel.Scope.SESSION && g.session == id)) {
                hits.add(g);
            }
        }
        hits.sort(SPEC_ORDER);
        for (G g : hits) {
            resting.remove(g);
            out.add(new Cancelled(g.session, g.clOrdId, 0, g.orderId, g.leaves(), reason));
            out.add(new MdDelete(g.symbol, ++sym(g.symbol).seq, g.orderId));
        }
    }

    private void halt(String symbol, boolean halted) {
        Sym s = sym(symbol);
        s.halted = halted;
        out.add(new MdHalt(symbol, ++s.seq, halted));
    }

    private void endOfDay() {
        if (closed) {
            return;
        }
        List<SymbolDigest> digests = BookDigests.perSymbol(sortedOrders());
        out.add(new BookDigest(digests, BookDigests.shardDigest(digests)));
        cancelWhere(MassCancel.Scope.ALL, 0, CancelReason.END_OF_DAY);
        closed = true;
    }

    // ---- State ---------------------------------------------------------------------

    private List<OrderView> sortedOrders() {
        List<G> sorted = new ArrayList<>(resting);
        sorted.sort(SPEC_ORDER);
        List<OrderView> views = new ArrayList<>(sorted.size());
        for (G g : sorted) {
            views.add(new OrderView(g.symbol, g.side, g.price, g.priority, g.orderId, g.session, g.clOrdId,
                    g.account, g.qty, g.filled));
        }
        return views;
    }

    @Override
    public StateView stateView() {
        List<Sym> syms = new ArrayList<>(symbols);
        syms.sort(Comparator.comparing(s -> s.name));
        List<SymbolView> sv = new ArrayList<>();
        for (Sym s : syms) {
            sv.add(new SymbolView(s.name, s.seq, s.halted));
        }
        List<long[]> hw = new ArrayList<>(highWater);
        hw.sort(Comparator.comparingLong(e -> e[0]));
        List<DedupEntry> dedup = new ArrayList<>();
        for (long[] e : hw) {
            dedup.add(new DedupEntry(e[0], e[1]));
        }
        return new StateView(sv, sortedOrders(), dedup, closed);
    }
}
