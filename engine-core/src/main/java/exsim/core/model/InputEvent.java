package exsim.core.model;

import exsim.core.codec.Canonical;
import java.util.Objects;

/**
 * One record of an {@code orders} partition: the engine's only input. Client messages
 * carry a session id and a clOrdId that strictly increases per session (starting at 1).
 * Control messages are written by the risk service.
 */
public sealed interface InputEvent {

    record NewOrder(long sessionId, long clOrdId, long account, String symbol,
                    Side side, OrdType type, long qty, long price) implements InputEvent {
        public NewOrder {
            requireClOrdId(clOrdId);
            Canonical.checkSymbol(symbol);
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
        }
    }

    /** Cancels the order whose current clOrdId is {@code origClOrdId}. */
    record CancelOrder(long sessionId, long clOrdId, long origClOrdId) implements InputEvent {
        public CancelOrder {
            requireClOrdId(clOrdId);
        }
    }

    /** Cancel-replace. {@code newQty} is the new total order quantity, including filled shares. */
    record ReplaceOrder(long sessionId, long clOrdId, long origClOrdId, long newQty, long newPrice)
            implements InputEvent {
        public ReplaceOrder {
            requireClOrdId(clOrdId);
        }
    }

    record MassCancel(Scope scope, long id) implements InputEvent {
        public enum Scope { ACCOUNT, SESSION, ALL }

        public MassCancel {
            Objects.requireNonNull(scope, "scope");
        }
    }

    record Halt(String symbol) implements InputEvent {
        public Halt {
            Canonical.checkSymbol(symbol);
        }
    }

    record Resume(String symbol) implements InputEvent {
        public Resume {
            Canonical.checkSymbol(symbol);
        }
    }

    record EndOfDay() implements InputEvent {
    }

    private static void requireClOrdId(long clOrdId) {
        if (clOrdId < 1) {
            throw new IllegalArgumentException("clOrdId must be >= 1: " + clOrdId);
        }
    }
}
