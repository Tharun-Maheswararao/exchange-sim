package exsim.core;

/** Looks up a resting order by its session and current clOrdId. */
record ClientKey(long sessionId, long clOrdId) {
}
