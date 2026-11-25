package exsim.core;

import exsim.core.codec.Canonical;
import exsim.core.codec.CanonicalReader;
import exsim.core.hash.HashChain;
import exsim.core.hash.XxHash64;
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
import exsim.core.state.StateCodec;
import exsim.core.state.StateView;
import exsim.core.state.SymbolView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic price-time matching engine for one shard. Single-threaded; the only
 * input is {@link #apply}. Rules are specified in {@code spec/matching.md}.
 *
 * <p>Hash maps are used only for lookups. Everything that is iterated to produce
 * outputs, digests or snapshots uses sorted structures.
 */
public final class MatchingEngine implements Engine {
    public static final int SNAPSHOT_FORMAT = 1;
    public static final long MAX_OFFSET = (1L << 56) - 1;
    private static final long SNAPSHOT_MAGIC = 0x3153_5041_4E53_5845L; // "EXSNAPS1" little-endian

    private final int shard;
    private final TreeMap<String, Book> books = new TreeMap<>();
    private final HashMap<ClientKey, Order> byClient = new HashMap<>();
    private final HashMap<Long, Long> highWater = new HashMap<>();
    private boolean endOfDay;
    private long lastOffset = -1;
    private long chain = HashChain.INITIAL;
    private final Canonical scratch = new Canonical(4096);

    // Per-event working state.
    private long offset;
    private int matchSeq;
    private ArrayList<Output> out;

    public MatchingEngine(int shard) {
        if (shard < 0 || shard > 255) {
            throw new IllegalArgumentException("shard must be 0-255: " + shard);
        }
        this.shard = shard;
    }

    /** Engine order id: shard in the top 8 bits, the NewOrder's offset in the low 56. */
    public static long orderId(int shard, long offset) {
        return ((long) shard << 56) | offset;
    }

    public int shard() {
        return shard;
    }

    @Override
    public long chainValue() {
        return chain;
    }

    @Override
    public long lastOffset() {
        return lastOffset;
    }

    @Override
    public List<Output> apply(long offset, InputEvent event) {
        Objects.requireNonNull(event, "event");
        if (offset <= lastOffset) {
            throw new IllegalArgumentException("offset " + offset + " is not after " + lastOffset);
        }
        if (offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset too large: " + offset);
        }
        this.offset = offset;
        this.matchSeq = 0;
        this.out = new ArrayList<>();
        switch (event) {
            case NewOrder o -> onNewOrder(o);
            case CancelOrder c -> onCancel(c);
            case ReplaceOrder r -> onReplace(r);
            case MassCancel m -> massCancel(m.scope(), m.id(), CancelReason.MASS_CANCEL);
            case Halt h -> setHalted(h.symbol(), true);
            case Resume r -> setHalted(r.symbol(), false);
            case EndOfDay e -> onEndOfDay();
        }
        List<Output> result = List.copyOf(out);
        out = null;
        chain = HashChain.next(chain, offset, result, scratch);
        lastOffset = offset;
        return result;
    }

    // ---- Client messages ---------------------------------------------------------

    private boolean isDuplicate(long sessionId, long clOrdId) {
        Long hw = highWater.get(sessionId);
        if (hw != null && clOrdId <= hw) {
            out.add(new DuplicateIgnored(sessionId, clOrdId));
            return true;
        }
        highWater.put(sessionId, clOrdId);
        return false;
    }

    private void onNewOrder(NewOrder o) {
        if (isDuplicate(o.sessionId(), o.clOrdId())) {
            return;
        }
        Book book = books.computeIfAbsent(o.symbol(), Book::new);
        Reject reject = null;
        if (endOfDay) {
            reject = Reject.MARKET_CLOSED;
        } else if (o.qty() <= 0) {
            reject = Reject.INVALID_QTY;
        } else if (o.type() != OrdType.MARKET && !Prices.onTick(o.price())) {
            reject = Reject.INVALID_PRICE;
        } else if (book.halted) {
            reject = Reject.HALTED;
        }
        long limit = o.price();
        if (reject == null && o.type() == OrdType.MARKET) {
            Order touch = book.best(o.side().opposite());
            if (touch == null) {
                reject = Reject.NO_LIQUIDITY;
            } else {
                limit = o.side() == Side.BUY ? Prices.buyCollar(touch.price) : Prices.sellCollar(touch.price);
            }
        }
        if (reject != null) {
            out.add(new Rejected(o.sessionId(), o.clOrdId(), reject));
            return;
        }

        Order order = new Order(orderId(shard, offset), o.sessionId(), o.clOrdId(), o.account(),
                o.symbol(), o.side(), limit, offset, o.qty());
        out.add(new Accepted(o.sessionId(), o.clOrdId(), order.orderId, o.symbol(), o.side(), o.type(),
                o.qty(), limit));
        boolean selfTrade = match(book, order);
        if (selfTrade || order.leaves() == 0) {
            return;
        }
        if (o.type() == OrdType.LIMIT) {
            rest(book, order);
        } else {
            out.add(new Cancelled(order.sessionId, order.clOrdId, 0, order.orderId, order.leaves(),
                    CancelReason.IOC_REMAINDER));
        }
    }

    private void onCancel(CancelOrder c) {
        if (isDuplicate(c.sessionId(), c.clOrdId())) {
            return;
        }
        if (endOfDay) {
            out.add(new CancelRejected(c.sessionId(), c.clOrdId(), c.origClOrdId(), Reject.MARKET_CLOSED));
            return;
        }
        Order order = byClient.get(new ClientKey(c.sessionId(), c.origClOrdId()));
        if (order == null) {
            out.add(new CancelRejected(c.sessionId(), c.clOrdId(), c.origClOrdId(), Reject.UNKNOWN_ORDER));
            return;
        }
        Book book = books.get(order.symbol);
        long leaves = order.leaves();
        unrest(book, order);
        out.add(new Cancelled(order.sessionId, order.clOrdId, c.clOrdId(), order.orderId, leaves,
                CancelReason.REQUESTED));
        out.add(new MdDelete(book.symbol, book.nextSeq(), order.orderId));
    }

    private void onReplace(ReplaceOrder r) {
        if (isDuplicate(r.sessionId(), r.clOrdId())) {
            return;
        }
        Order order = endOfDay ? null : byClient.get(new ClientKey(r.sessionId(), r.origClOrdId()));
        Reject reject = null;
        if (endOfDay) {
            reject = Reject.MARKET_CLOSED;
        } else if (order == null) {
            reject = Reject.UNKNOWN_ORDER;
        } else if (r.newQty() <= 0) {
            reject = Reject.INVALID_QTY;
        } else if (!Prices.onTick(r.newPrice())) {
            reject = Reject.INVALID_PRICE;
        } else if (books.get(order.symbol).halted) {
            reject = Reject.HALTED;
        }
        if (reject != null) {
            out.add(new CancelRejected(r.sessionId(), r.clOrdId(), r.origClOrdId(), reject));
            return;
        }

        Book book = books.get(order.symbol);
        long oldLeaves = order.leaves();
        long newLeaves = r.newQty() - order.filled;
        if (newLeaves <= 0) {
            unrest(book, order);
            out.add(new Cancelled(order.sessionId, order.clOrdId, r.clOrdId(), order.orderId, oldLeaves,
                    CancelReason.REPLACED_TO_ZERO));
            out.add(new MdDelete(book.symbol, book.nextSeq(), order.orderId));
            return;
        }

        boolean keepPriority = r.newPrice() == order.price && newLeaves <= oldLeaves;
        if (keepPriority) {
            byClient.remove(order.key());
            order.clOrdId = r.clOrdId();
            order.qty = r.newQty();
            byClient.put(order.key(), order);
            out.add(new Replaced(order.sessionId, order.clOrdId, r.origClOrdId(), order.orderId, order.qty,
                    order.price, order.leaves(), true));
            if (order.leaves() < oldLeaves) {
                out.add(new MdReduce(book.symbol, book.nextSeq(), order.orderId, order.leaves()));
            }
            return;
        }

        // Loses priority: leaves the book, then re-enters as an aggressor at this offset.
        unrest(book, order);
        order.clOrdId = r.clOrdId();
        order.qty = r.newQty();
        order.price = r.newPrice();
        order.priority = offset;
        out.add(new Replaced(order.sessionId, order.clOrdId, r.origClOrdId(), order.orderId, order.qty,
                order.price, order.leaves(), false));
        out.add(new MdDelete(book.symbol, book.nextSeq(), order.orderId));
        boolean selfTrade = match(book, order);
        if (!selfTrade && order.leaves() > 0) {
            rest(book, order);
        }
    }

    // ---- Matching ----------------------------------------------------------------

    /** Matches an aggressor against the book. Returns true if it was cancelled by self-trade prevention. */
    private boolean match(Book book, Order aggressor) {
        Side passiveSide = aggressor.side.opposite();
        while (aggressor.leaves() > 0) {
            Order resting = book.best(passiveSide);
            if (resting == null || !crosses(aggressor, resting.price)) {
                return false;
            }
            if (resting.account == aggressor.account) {
                out.add(new Cancelled(aggressor.sessionId, aggressor.clOrdId, 0, aggressor.orderId,
                        aggressor.leaves(), CancelReason.SELF_TRADE));
                return true;
            }
            long qty = Math.min(aggressor.leaves(), resting.leaves());
            int seq = ++matchSeq;
            resting.filled += qty;
            aggressor.filled += qty;
            out.add(new Fill(resting.sessionId, resting.clOrdId, resting.orderId, resting.symbol, resting.side,
                    resting.price, qty, resting.leaves(), false, seq));
            out.add(new Fill(aggressor.sessionId, aggressor.clOrdId, aggressor.orderId, aggressor.symbol,
                    aggressor.side, resting.price, qty, aggressor.leaves(), true, seq));
            out.add(new MdExecute(book.symbol, book.nextSeq(), resting.orderId, resting.price, qty, seq));
            if (resting.leaves() == 0) {
                unrest(book, resting);
            }
        }
        return false;
    }

    private static boolean crosses(Order aggressor, long restingPrice) {
        return aggressor.side == Side.BUY ? restingPrice <= aggressor.price : restingPrice >= aggressor.price;
    }

    private void rest(Book book, Order order) {
        book.add(order);
        byClient.put(order.key(), order);
        out.add(new MdAdd(book.symbol, book.nextSeq(), order.orderId, order.side, order.price, order.leaves()));
    }

    private void unrest(Book book, Order order) {
        book.remove(order);
        byClient.remove(order.key());
    }

    // ---- Control messages --------------------------------------------------------

    private void massCancel(MassCancel.Scope scope, long id, CancelReason reason) {
        List<Order> victims = new ArrayList<>();
        for (Book book : books.values()) {
            for (Side side : Side.values()) {
                for (ArrayDeque<Order> level : book.levels(side).values()) {
                    for (Order o : level) {
                        boolean hit = switch (scope) {
                            case ALL -> true;
                            case ACCOUNT -> o.account == id;
                            case SESSION -> o.sessionId == id;
                        };
                        if (hit) {
                            victims.add(o);
                        }
                    }
                }
            }
        }
        for (Order o : victims) {
            Book book = books.get(o.symbol);
            long leaves = o.leaves();
            unrest(book, o);
            out.add(new Cancelled(o.sessionId, o.clOrdId, 0, o.orderId, leaves, reason));
            out.add(new MdDelete(book.symbol, book.nextSeq(), o.orderId));
        }
    }

    private void setHalted(String symbol, boolean halted) {
        Book book = books.computeIfAbsent(symbol, Book::new);
        book.halted = halted;
        out.add(new MdHalt(symbol, book.nextSeq(), halted));
    }

    private void onEndOfDay() {
        if (endOfDay) {
            return;
        }
        List<SymbolDigest> digests = BookDigests.perSymbol(restingOrders());
        out.add(new BookDigest(digests, BookDigests.shardDigest(digests)));
        massCancel(MassCancel.Scope.ALL, 0, CancelReason.END_OF_DAY);
        endOfDay = true;
    }

    // ---- State, snapshots --------------------------------------------------------

    private List<OrderView> restingOrders() {
        List<OrderView> views = new ArrayList<>(byClient.size());
        for (Book book : books.values()) {
            for (Side side : Side.values()) {
                for (ArrayDeque<Order> level : book.levels(side).values()) {
                    for (Order o : level) {
                        views.add(o.view());
                    }
                }
            }
        }
        return views;
    }

    @Override
    public StateView stateView() {
        List<SymbolView> symbols = new ArrayList<>(books.size());
        for (Book b : books.values()) {
            symbols.add(new SymbolView(b.symbol, b.mdSeq, b.halted));
        }
        List<DedupEntry> dedup = new ArrayList<>(highWater.size());
        new TreeMap<>(highWater).forEach((session, hw) -> dedup.add(new DedupEntry(session, hw)));
        return new StateView(symbols, restingOrders(), dedup, endOfDay);
    }

    /**
     * Canonical snapshot: the same state always produces the same bytes. Layout in
     * {@code spec/digest.md}.
     */
    public byte[] snapshot() {
        Canonical state = new Canonical(4096);
        StateCodec.encode(stateView(), state);
        long digest = XxHash64.hash(state.array(), 0, state.length(), 0);
        Canonical w = new Canonical(state.length() + 48);
        w.i64(SNAPSHOT_MAGIC).i32(SNAPSHOT_FORMAT).i32(shard).i64(lastOffset).i64(chain)
                .i32(state.length()).bytes(state.array(), 0, state.length()).i64(digest);
        return w.toByteArray();
    }

    /** Rebuilds an engine from {@link #snapshot()} output, verifying the state digest. */
    public static MatchingEngine restore(byte[] snapshot) {
        CanonicalReader r = new CanonicalReader(snapshot);
        if (r.i64() != SNAPSHOT_MAGIC) {
            throw new IllegalArgumentException("not an engine snapshot");
        }
        int format = r.i32();
        if (format != SNAPSHOT_FORMAT) {
            throw new IllegalArgumentException("unsupported snapshot format " + format);
        }
        int shard = r.i32();
        long last = r.i64();
        long chainValue = r.i64();
        int len = r.i32();
        int start = r.position();
        r.skip(len);
        long digest = r.i64();
        if (r.remaining() != 0) {
            throw new IllegalArgumentException("trailing bytes after snapshot");
        }
        if (XxHash64.hash(snapshot, start, len, 0) != digest) {
            throw new IllegalStateException("snapshot digest mismatch");
        }
        StateView s = StateCodec.decode(new CanonicalReader(snapshot, start, len));

        MatchingEngine e = new MatchingEngine(shard);
        e.lastOffset = last;
        e.chain = chainValue;
        e.endOfDay = s.endOfDay();
        for (SymbolView sv : s.symbols()) {
            Book b = new Book(sv.symbol());
            b.mdSeq = sv.mdSeq();
            b.halted = sv.halted();
            e.books.put(b.symbol, b);
        }
        // Canonical order is priority order within each level, so appending rebuilds the queues.
        for (OrderView o : s.orders()) {
            Order ord = new Order(o.orderId(), o.sessionId(), o.clOrdId(), o.account(), o.symbol(), o.side(),
                    o.price(), o.priority(), o.qty());
            ord.filled = o.filled();
            Book b = e.books.get(o.symbol());
            if (b == null) {
                throw new IllegalArgumentException("order for unknown symbol " + o.symbol());
            }
            b.add(ord);
            e.byClient.put(ord.key(), ord);
        }
        for (DedupEntry d : s.dedup()) {
            e.highWater.put(d.sessionId(), d.highWater());
        }
        return e;
    }
}
