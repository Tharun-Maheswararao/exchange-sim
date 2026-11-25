package exsim.core.codec;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads bytes written by {@link Canonical}. Throws on truncated or malformed input. */
public final class CanonicalReader {
    private final byte[] buf;
    private final int end;
    private int pos;

    public CanonicalReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public CanonicalReader(byte[] buf, int off, int len) {
        Objects.checkFromIndexSize(off, len, buf.length);
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    private void need(int n) {
        if (end - pos < n) {
            throw new IllegalArgumentException("truncated input at byte " + pos);
        }
    }

    public int u8() {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public boolean bool() {
        int v = u8();
        if (v > 1) {
            throw new IllegalArgumentException("invalid boolean byte " + v);
        }
        return v == 1;
    }

    public int i32() {
        need(4);
        int v = (int) Canonical.INT.get(buf, pos);
        pos += 4;
        return v;
    }

    public long i64() {
        need(8);
        long v = (long) Canonical.LONG.get(buf, pos);
        pos += 8;
        return v;
    }

    public String symbol() {
        need(Canonical.SYMBOL_BYTES);
        int n = Canonical.SYMBOL_BYTES;
        while (n > 0 && buf[pos + n - 1] == ' ') {
            n--;
        }
        String s = new String(buf, pos, n, StandardCharsets.US_ASCII);
        pos += Canonical.SYMBOL_BYTES;
        return Canonical.checkSymbol(s);
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return end - pos;
    }
}
