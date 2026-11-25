package exsim.core.state;

import java.util.List;

/**
 * Complete replicated state of one shard, in canonical order (see {@code spec/digest.md}):
 * symbols sorted by name; orders by symbol, BUY before SELL, best price first, then
 * priority; dedup entries by session id.
 */
public record StateView(List<SymbolView> symbols, List<OrderView> orders,
                        List<DedupEntry> dedup, boolean endOfDay) {
    public StateView {
        symbols = List.copyOf(symbols);
        orders = List.copyOf(orders);
        dedup = List.copyOf(dedup);
    }
}
