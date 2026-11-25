package exsim.core;

import exsim.core.model.Side;
import exsim.core.state.OrderView;

/** Mutable resting order. Identity equality on purpose: the book removes by reference. */
final class Order {
    final long orderId;
    final long sessionId;
    final long account;
    final String symbol;
    final Side side;
    long clOrdId;
    long price;
    long priority;
    long qty;
    long filled;

    Order(long orderId, long sessionId, long clOrdId, long account, String symbol, Side side,
          long price, long priority, long qty) {
        this.orderId = orderId;
        this.sessionId = sessionId;
        this.clOrdId = clOrdId;
        this.account = account;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.priority = priority;
        this.qty = qty;
    }

    long leaves() {
        return qty - filled;
    }

    ClientKey key() {
        return new ClientKey(sessionId, clOrdId);
    }

    OrderView view() {
        return new OrderView(symbol, side, price, priority, orderId, sessionId, clOrdId, account, qty, filled);
    }
}
