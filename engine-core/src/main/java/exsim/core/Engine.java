package exsim.core;

import exsim.core.model.InputEvent;
import exsim.core.model.Output;
import exsim.core.model.SymbolDigest;
import exsim.core.state.BookDigests;
import exsim.core.state.StateCodec;
import exsim.core.state.StateView;
import java.util.List;

/**
 * A deterministic shard engine: applies the records of one {@code orders} partition in
 * offset order. Implemented by the real engine and by the golden reference model.
 */
public interface Engine {

    /** Applies one event. Offsets must strictly increase but may have gaps. */
    List<Output> apply(long offset, InputEvent event);

    /** Current hash chain value. */
    long chainValue();

    /** Last applied offset, or -1 before the first event. */
    long lastOffset();

    /** Full replicated state in canonical order. */
    StateView stateView();

    default long stateDigest() {
        return StateCodec.digest(stateView());
    }

    default List<SymbolDigest> bookDigests() {
        return BookDigests.perSymbol(stateView().orders());
    }
}
