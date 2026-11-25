package exsim.core;

import exsim.core.model.Side;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** One symbol's order book: sorted price levels with FIFO queues. */
final class Book {
    final String symbol;
    boolean halted;
    long mdSeq;

    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    Book(String symbol) {
        this.symbol = symbol;
    }

    /** Levels for one side, best price first. */
    NavigableMap<Long, ArrayDeque<Order>> levels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    Order best(Side side) {
        Map.Entry<Long, ArrayDeque<Order>> top = levels(side).firstEntry();
        return top == null ? null : top.getValue().peekFirst();
    }

    void add(Order o) {
        levels(o.side).computeIfAbsent(o.price, p -> new ArrayDeque<>()).addLast(o);
    }

    void remove(Order o) {
        NavigableMap<Long, ArrayDeque<Order>> side = levels(o.side);
        ArrayDeque<Order> level = side.get(o.price);
        if (level == null || !level.remove(o)) {
            throw new IllegalStateException("order " + o.orderId + " is not on the book");
        }
        if (level.isEmpty()) {
            side.remove(o.price);
        }
    }

    long nextSeq() {
        return ++mdSeq;
    }
}
