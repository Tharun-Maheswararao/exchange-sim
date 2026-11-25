package exsim.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import exsim.core.MatchingEngine;
import exsim.core.model.InputEvent;
import exsim.core.model.InputEvent.EndOfDay;
import exsim.core.model.Output;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * Differential testing: random order streams through the real engine and the golden
 * model must give identical outputs for every event, identical chain values and
 * identical state. Together the two tests below replay well over 10^6 events.
 */
class DifferentialTest {

    @Property(tries = 300)
    void engineMatchesGolden(@ForAll long seed, @ForAll @IntRange(min = 200, max = 3000) int events) {
        runDifferential(seed, events, 250);
    }

    @Test
    void oneMillionEventStream() {
        long total = runDifferential(20_261_218L, 1_000_000, 10_000);
        assertEquals(1_000_001, total);
    }

    /** Returns the number of events applied (including the final EndOfDay). */
    static long runDifferential(long seed, int events, int fullCheckEvery) {
        int shard = Math.floorMod(seed, 256);
        MatchingEngine engine = new MatchingEngine(shard);
        GoldenEngine golden = new GoldenEngine(shard);
        RandomFlow flow = new RandomFlow(seed);
        long offset = Math.floorMod(seed, 1000);
        long applied = 0;
        for (int i = 0; i <= events; i++) {
            InputEvent event = i == events ? new EndOfDay() : flow.next();
            List<Output> real = engine.apply(offset, event);
            List<Output> reference = golden.apply(offset, event);
            if (!real.equals(reference)) {
                fail("seed " + seed + ", event " + i + " at offset " + offset + ": " + event
                        + "\n engine: " + real + "\n golden: " + reference);
            }
            applied++;
            if (i % fullCheckEvery == 0 || i == events) {
                assertEquals(golden.stateView(), engine.stateView(), "state differs, seed " + seed + " event " + i);
            }
            // Kafka offsets are not contiguous under transactions; leave occasional gaps.
            offset += (i % 7 == 0) ? 2 : 1;
        }
        assertEquals(golden.chainValue(), engine.chainValue(), "chain differs, seed " + seed);
        assertEquals(golden.stateDigest(), engine.stateDigest(), "state digest differs, seed " + seed);
        return applied;
    }
}
