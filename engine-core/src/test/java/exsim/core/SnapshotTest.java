package exsim.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import exsim.core.model.InputEvent;
import exsim.core.model.InputEvent.CancelOrder;
import exsim.core.model.InputEvent.Halt;
import exsim.core.model.InputEvent.NewOrder;
import exsim.core.model.InputEvent.ReplaceOrder;
import exsim.core.model.OrdType;
import exsim.core.model.Side;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    private static NewOrder limit(long session, long cl, String sym, Side side, long qty, long price) {
        return new NewOrder(session, cl, session, sym, side, OrdType.LIMIT, qty, price);
    }

    private static final List<InputEvent> PREFIX = List.of(
            limit(1, 1, "AAA", Side.BUY, 100, 1_000_000),
            limit(1, 2, "AAA", Side.BUY, 50, 999_900),
            limit(2, 1, "AAA", Side.SELL, 30, 1_000_000),
            limit(2, 2, "BBB", Side.SELL, 70, 250_000),
            new ReplaceOrder(1, 3, 2, 20, 999_900),
            new Halt("CCC"),
            limit(3, 9, "AAA", Side.SELL, 10, 1_000_100));

    private static MatchingEngine engineAfterPrefix() {
        MatchingEngine e = new MatchingEngine(7);
        long offset = 10;
        for (InputEvent ev : PREFIX) {
            e.apply(offset, ev);
            offset += 3;
        }
        return e;
    }

    @Test
    void restoreReproducesStateAndContinuesIdentically() {
        MatchingEngine original = engineAfterPrefix();
        MatchingEngine restored = MatchingEngine.restore(original.snapshot());

        assertEquals(original.stateView(), restored.stateView());
        assertEquals(original.stateDigest(), restored.stateDigest());
        assertEquals(original.chainValue(), restored.chainValue());
        assertEquals(original.lastOffset(), restored.lastOffset());
        assertEquals(original.shard(), restored.shard());

        List<InputEvent> next = List.of(
                limit(4, 1, "AAA", Side.SELL, 200, 999_900),
                new CancelOrder(2, 3, 2),
                limit(2, 1, "AAA", Side.BUY, 5, 1_000_000)); // duplicate clOrdId for session 2
        long offset = original.lastOffset() + 1;
        for (InputEvent ev : next) {
            assertEquals(original.apply(offset, ev), restored.apply(offset, ev));
            offset++;
        }
        assertEquals(original.chainValue(), restored.chainValue());
        assertEquals(original.stateDigest(), restored.stateDigest());
    }

    @Test
    void snapshotBytesAreCanonical() {
        byte[] first = engineAfterPrefix().snapshot();
        assertArrayEquals(first, engineAfterPrefix().snapshot());
        assertArrayEquals(first, MatchingEngine.restore(first).snapshot());
    }

    @Test
    void rejectsCorruptedSnapshot() {
        byte[] snap = engineAfterPrefix().snapshot();
        snap[snap.length / 2] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> MatchingEngine.restore(snap));
    }

    @Test
    void rejectsForeignBytes() {
        assertThrows(IllegalArgumentException.class, () -> MatchingEngine.restore(new byte[64]));
    }

    @Test
    void rejectsNonIncreasingOffsets() {
        MatchingEngine e = new MatchingEngine(0);
        e.apply(5, new Halt("AAA"));
        assertThrows(IllegalArgumentException.class, () -> e.apply(5, new Halt("AAA")));
    }
}
