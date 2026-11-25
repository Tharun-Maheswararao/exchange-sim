package exsim.core.state;

/** Per-symbol state that is not part of the book: market data sequence and halt flag. */
public record SymbolView(String symbol, long mdSeq, boolean halted) {
}
