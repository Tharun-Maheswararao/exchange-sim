package exsim.core.model;

import exsim.core.codec.Canonical;

/** Book digest of one symbol, as defined in {@code spec/digest.md}. */
public record SymbolDigest(String symbol, long digest) {
    public SymbolDigest {
        Canonical.checkSymbol(symbol);
    }
}
