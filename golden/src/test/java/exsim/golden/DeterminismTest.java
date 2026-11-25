package exsim.golden;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import exsim.core.MatchingEngine;
import exsim.core.model.InputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The hash chain is only meaningful if the engine is fully deterministic. */
class DeterminismTest {

    private static List<InputEvent> stream(long seed, int n) {
        RandomFlow flow = new RandomFlow(seed);
        List<InputEvent> events = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            events.add(flow.next());
        }
        return events;
    }

    private static long run(List<InputEvent> events) {
        MatchingEngine e = new MatchingEngine(1);
        for (int i = 0; i < events.size(); i++) {
            e.apply(i, events.get(i));
        }
        return e.chainValue();
    }

    @Test
    void sameInputGivesSameChain() {
        List<InputEvent> events = stream(42, 50_000);
        assertEquals(run(events), run(events));
    }

    @Test
    void concurrentEnginesDoNotInterfere() throws Exception {
        List<InputEvent> events = stream(7, 50_000);
        long expected = run(events);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> run(events)));
            }
            for (Future<Long> f : results) {
                assertEquals(expected, f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotAtAnyPointContinuesIdentically() {
        List<InputEvent> events = stream(99, 20_000);
        long uninterrupted = run(events);
        for (int cut : new int[] {1, 137, 5_000, 12_345, 19_999}) {
            MatchingEngine e = new MatchingEngine(1);
            for (int i = 0; i < cut; i++) {
                e.apply(i, events.get(i));
            }
            byte[] snap = e.snapshot();
            MatchingEngine restored = MatchingEngine.restore(snap);
            assertArrayEquals(snap, restored.snapshot(), "snapshot not canonical at cut " + cut);
            for (int i = cut; i < events.size(); i++) {
                restored.apply(i, events.get(i));
            }
            assertEquals(uninterrupted, restored.chainValue(), "chain differs after restore at cut " + cut);
        }
    }
}
