package exsim.golden;

import exsim.core.MatchingEngine;
import exsim.core.model.InputEvent.CancelOrder;
import exsim.core.model.InputEvent.NewOrder;
import exsim.core.model.InputEvent.ReplaceOrder;
import exsim.core.model.OrdType;
import exsim.core.model.Output;
import exsim.core.model.Side;
import java.math.BigDecimal;
import java.util.List;

/** Message builders for scenario tests. By default an order's account equals its session id. */
final class Fixtures {
    static final int SHARD = 3;

    private Fixtures() {
    }

    static long oid(long offset) {
        return MatchingEngine.orderId(SHARD, offset);
    }

    /** "100.25" → 1_002_500 (4 implied decimals). */
    static long px(String price) {
        return new BigDecimal(price).movePointRight(4).longValueExact();
    }

    static NewOrder limit(long session, long cl, String sym, Side side, long qty, String price) {
        return new NewOrder(session, cl, session, sym, side, OrdType.LIMIT, qty, px(price));
    }

    static NewOrder limitAcct(long session, long cl, long account, String sym, Side side, long qty, String price) {
        return new NewOrder(session, cl, account, sym, side, OrdType.LIMIT, qty, px(price));
    }

    static NewOrder ioc(long session, long cl, String sym, Side side, long qty, String price) {
        return new NewOrder(session, cl, session, sym, side, OrdType.IOC, qty, px(price));
    }

    static NewOrder market(long session, long cl, String sym, Side side, long qty) {
        return new NewOrder(session, cl, session, sym, side, OrdType.MARKET, qty, 0);
    }

    static CancelOrder cancel(long session, long cl, long orig) {
        return new CancelOrder(session, cl, orig);
    }

    static ReplaceOrder replace(long session, long cl, long orig, long qty, String price) {
        return new ReplaceOrder(session, cl, orig, qty, px(price));
    }

    static <T extends Output> List<T> only(List<Output> outputs, Class<T> type) {
        return outputs.stream().filter(type::isInstance).map(type::cast).toList();
    }

    static Output last(List<Output> outputs) {
        return outputs.get(outputs.size() - 1);
    }
}
