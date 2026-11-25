package exsim.core.codec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Growable little-endian byte writer used for every canonical encoding: output records
 * (hash chain), book digests, engine state and snapshots. Layouts are defined in
 * {@code spec/digest.md}; the same state must always produce the same bytes.
 */
public final class Canonical {
    static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Symbols are encoded as exactly 8 ASCII bytes, right-padded with spaces (as in ITCH). */
    public static final int SYMBOL_BYTES = 8;

    private byte[] buf;
    private int len;

    public Canonical() {
        this(256);
    }

    public Canonical(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    public Canonical reset() {
        len = 0;
        return this;
    }

    private void ensure(int n) {
        if (len + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
    }

    public Canonical u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public Canonical bool(boolean v) {
        return u8(v ? 1 : 0);
    }

    public Canonical i32(int v) {
        ensure(4);
        INT.set(buf, len, v);
        len += 4;
        return this;
    }

    public Canonical i64(long v) {
        ensure(8);
        LONG.set(buf, len, v);
        len += 8;
        return this;
    }

    public Canonical bytes(byte[] src, int off, int n) {
        ensure(n);
        System.arraycopy(src, off, buf, len, n);
        len += n;
        return this;
    }

    public Canonical symbol(String s) {
        checkSymbol(s);
        ensure(SYMBOL_BYTES);
        for (int i = 0; i < SYMBOL_BYTES; i++) {
            buf[len + i] = i < s.length() ? (byte) s.charAt(i) : (byte) ' ';
        }
        len += SYMBOL_BYTES;
        return this;
    }

    /** Backing array; only the first {@link #length()} bytes are meaningful. */
    public byte[] array() {
        return buf;
    }

    public int length() {
        return len;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    /** Validates a symbol: 1 to 8 printable ASCII characters, no spaces. */
    public static String checkSymbol(String s) {
        if (s == null || s.isEmpty() || s.length() > SYMBOL_BYTES) {
            throw new IllegalArgumentException("symbol must be 1-8 characters: " + s);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException("symbol must be printable ASCII without spaces: " + s);
            }
        }
        return s;
    }
}
