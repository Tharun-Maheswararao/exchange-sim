package exsim.core.state;

/** Duplicate-check high-water mark for one session on this shard. */
public record DedupEntry(long sessionId, long highWater) {
}
