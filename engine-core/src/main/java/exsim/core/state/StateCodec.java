package exsim.core.state;

import exsim.core.codec.Canonical;
import exsim.core.codec.CanonicalReader;
import exsim.core.hash.XxHash64;
import exsim.core.model.Side;
import java.util.ArrayList;
import java.util.List;

/** Canonical encoding of {@link StateView}; the state digest is XXH64 of these bytes. */
public final class StateCodec {
    private StateCodec() {
    }

    public static void encode(StateView s, Canonical w) {
        w.i32(s.symbols().size());
        for (SymbolView v : s.symbols()) {
            w.symbol(v.symbol()).i64(v.mdSeq()).bool(v.halted());
        }
        w.i32(s.orders().size());
        for (OrderView o : s.orders()) {
            w.symbol(o.symbol()).u8(o.side().code()).i64(o.price()).i64(o.priority())
                    .i64(o.orderId()).i64(o.sessionId()).i64(o.clOrdId()).i64(o.account())
                    .i64(o.qty()).i64(o.filled());
        }
        w.i32(s.dedup().size());
        for (DedupEntry d : s.dedup()) {
            w.i64(d.sessionId()).i64(d.highWater());
        }
        w.bool(s.endOfDay());
    }

    public static StateView decode(CanonicalReader r) {
        int ns = count(r);
        List<SymbolView> symbols = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            symbols.add(new SymbolView(r.symbol(), r.i64(), r.bool()));
        }
        int no = count(r);
        List<OrderView> orders = new ArrayList<>(no);
        for (int i = 0; i < no; i++) {
            orders.add(new OrderView(r.symbol(), Side.fromCode(r.u8()), r.i64(), r.i64(), r.i64(),
                    r.i64(), r.i64(), r.i64(), r.i64(), r.i64()));
        }
        int nd = count(r);
        List<DedupEntry> dedup = new ArrayList<>(nd);
        for (int i = 0; i < nd; i++) {
            dedup.add(new DedupEntry(r.i64(), r.i64()));
        }
        boolean eod = r.bool();
        if (r.remaining() != 0) {
            throw new IllegalArgumentException("trailing bytes after state");
        }
        return new StateView(symbols, orders, dedup, eod);
    }

    public static long digest(StateView s) {
        Canonical w = new Canonical(1024);
        encode(s, w);
        return XxHash64.hash(w.array(), 0, w.length(), 0);
    }

    private static int count(CanonicalReader r) {
        int n = r.i32();
        if (n < 0) {
            throw new IllegalArgumentException("negative count " + n);
        }
        return n;
    }
}
