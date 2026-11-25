package exsim.golden;

import static org.junit.jupiter.api.Assertions.assertTrue;

import exsim.core.MatchingEngine;
import exsim.core.model.Output;
import exsim.core.model.Output.Cancelled;
import exsim.core.model.Output.Rejected;
import exsim.core.model.Output.CancelRejected;
import exsim.core.model.Output.Replaced;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Guards the differential test against a toothless generator: the random flow must
 * actually reach every engine path in meaningful numbers.
 */
class FlowCoverageTest {

    @Test
    void randomFlowReachesEveryPath() {
        Map<String, Integer> counts = new TreeMap<>();
        MatchingEngine engine = new MatchingEngine(0);
        RandomFlow flow = new RandomFlow(20_261_218L);
        int maxResting = 0;
        for (int i = 0; i < 200_000; i++) {
            for (Output o : engine.apply(i, flow.next())) {
                String key = switch (o) {
                    case Cancelled c -> "Cancelled." + c.reason();
                    case Rejected r -> "Rejected." + r.reason();
                    case CancelRejected r -> "CancelRejected." + r.reason();
                    case Replaced r -> r.keptPriority() ? "Replaced.kept" : "Replaced.lost";
                    default -> o.getClass().getSimpleName();
                };
                counts.merge(key, 1, Integer::sum);
            }
            if (i % 1000 == 0) {
                maxResting = Math.max(maxResting, engine.stateView().orders().size());
            }
        }
        System.out.println("flow coverage over 200k events: " + counts + ", max resting " + maxResting);

        String[] required = {
            "Accepted", "Fill", "MdAdd", "MdExecute", "MdReduce", "MdDelete", "MdHalt", "DuplicateIgnored",
            "Cancelled.REQUESTED", "Cancelled.IOC_REMAINDER", "Cancelled.SELF_TRADE", "Cancelled.MASS_CANCEL",
            "Cancelled.REPLACED_TO_ZERO", "Replaced.kept", "Replaced.lost",
            "Rejected.INVALID_QTY", "Rejected.INVALID_PRICE", "Rejected.HALTED", "Rejected.NO_LIQUIDITY",
            "CancelRejected.UNKNOWN_ORDER", "CancelRejected.HALTED", "CancelRejected.INVALID_QTY",
            "CancelRejected.INVALID_PRICE",
        };
        for (String key : required) {
            assertTrue(counts.getOrDefault(key, 0) >= 50, "path barely exercised: " + key + " = " + counts.get(key));
        }
        assertTrue(maxResting >= 20, "book too thin to test priority: max resting " + maxResting);
    }
}
