package exsim.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import exsim.core.MatchingEngine;
import exsim.core.model.InputEvent;
import exsim.core.model.Output;
import java.util.List;

/**
 * Feeds every event to both the real engine and the golden model and fails on the first
 * difference in outputs or chain value. Scenario tests therefore check both engines.
 */
final class Dual {
    private final MatchingEngine engine;
    private final GoldenEngine golden;
    private long offset;

    Dual() {
        this(Fixtures.SHARD);
    }

    Dual(int shard) {
        engine = new MatchingEngine(shard);
        golden = new GoldenEngine(shard);
    }

    List<Output> send(InputEvent event) {
        long off = offset++;
        List<Output> real = engine.apply(off, event);
        List<Output> reference = golden.apply(off, event);
        assertEquals(reference, real, () -> "engine and golden differ at offset " + off + " for " + event);
        assertEquals(golden.chainValue(), engine.chainValue(), "chain differs at offset " + off);
        assertEquals(golden.stateView(), engine.stateView(), "state differs at offset " + off);
        return real;
    }

    MatchingEngine engine() {
        return engine;
    }
}
