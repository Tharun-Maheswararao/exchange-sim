package exsim.core.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class XxHash64Test {

    private static long h(String s) {
        return XxHash64.hash(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void matchesReferenceVectors() {
        assertEquals(0xEF46DB3751D8E999L, h(""));
        assertEquals(0xD24EC4F1A98C6E5BL, h("a"));
        assertEquals(0x44BC2CF5AD770999L, h("abc"));
        // 39 bytes: exercises the 32-byte stripe loop, the 8-byte tail and the 4-byte tail.
        assertEquals(0xFBCEA83C8A378BF1L, h("Nobody inspects the spammish repetition"));
    }

    @Test
    void hashesASubRangeLikeACopy() {
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        for (int off = 0; off < 40; off += 7) {
            for (int len = 0; len < 120; len += 13) {
                byte[] copy = Arrays.copyOfRange(data, off, off + len);
                assertEquals(XxHash64.hash(copy, 0, len, 42), XxHash64.hash(data, off, len, 42));
            }
        }
    }
}
