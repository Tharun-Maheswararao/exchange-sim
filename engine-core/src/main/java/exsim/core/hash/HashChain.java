package exsim.core.hash;

import exsim.core.codec.Canonical;
import exsim.core.model.Output;
import java.util.List;

/**
 * Output hash chain: {@code h_n = XXH64(h_{n-1} | offset_n | canonical(outputs_n))},
 * advanced after every input event, including events that produce no output. Two
 * replicas have the same chain value if and only if they produced identical outputs
 * for identical offsets (up to hash collisions).
 */
public final class HashChain {
    /** Chain value before the first event. */
    public static final long INITIAL = 0L;

    private HashChain() {
    }

    public static long next(long previous, long offset, List<? extends Output> outputs, Canonical scratch) {
        scratch.reset().i64(previous).i64(offset);
        for (Output o : outputs) {
            o.encode(scratch);
        }
        return XxHash64.hash(scratch.array(), 0, scratch.length(), 0);
    }
}
