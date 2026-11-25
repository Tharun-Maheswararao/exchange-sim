package exsim.core.state;

import exsim.core.codec.Canonical;
import exsim.core.hash.XxHash64;
import exsim.core.model.SymbolDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Book digests use only feed-visible fields (side, price, order id, leaves quantity), so
 * a market data consumer such as the C++ feed handler can compute them too.
 */
public final class BookDigests {
    private BookDigests() {
    }

    /** Digest of one symbol's resting orders, given in canonical order. */
    public static long symbolDigest(String symbol, List<OrderView> orders) {
        Canonical w = new Canonical(16 + orders.size() * 25);
        w.symbol(symbol);
        for (OrderView o : orders) {
            w.u8(o.side().code()).i64(o.price()).i64(o.orderId()).i64(o.leaves());
        }
        return XxHash64.hash(w.array(), 0, w.length(), 0);
    }

    /**
     * Per-symbol digests from all resting orders in canonical order (orders of one symbol
     * are contiguous). Symbols with no resting orders are omitted.
     */
    public static List<SymbolDigest> perSymbol(List<OrderView> canonicalOrders) {
        List<SymbolDigest> result = new ArrayList<>();
        int i = 0;
        while (i < canonicalOrders.size()) {
            String symbol = canonicalOrders.get(i).symbol();
            int j = i;
            while (j < canonicalOrders.size() && canonicalOrders.get(j).symbol().equals(symbol)) {
                j++;
            }
            result.add(new SymbolDigest(symbol, symbolDigest(symbol, canonicalOrders.subList(i, j))));
            i = j;
        }
        return result;
    }

    /** Digest over per-symbol digests sorted by symbol. */
    public static long shardDigest(List<SymbolDigest> digests) {
        Canonical w = new Canonical(8 + digests.size() * 16);
        w.i32(digests.size());
        for (SymbolDigest d : digests) {
            w.symbol(d.symbol()).i64(d.digest());
        }
        return XxHash64.hash(w.array(), 0, w.length(), 0);
    }
}
