package pro.deta.orion.util.rle;

import org.junit.jupiter.api.Test;
import pro.deta.orion.util.OpenSSHKey;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

public class RLETCoderTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    public void testSimpleRLE() {
        byte[] data = { 0,1,2,3 };
        encodeAndDecodeTestShort(data, "000400010203");
        assertEquals(HEX.formatHex(RLETCoder.ENCODER_SHORT.encodeRLET(data, data)), "000400010203000400010203");
    }

    private void encodeAndDecodeTestShort(byte[] data, String expected) {
        byte[] result = RLETCoder.ENCODER_SHORT.encodeRLET(data);
        assertEquals(HEX.formatHex(result), expected);
        byte[][] original = RLETCoder.ENCODER_SHORT.decodeRLET(result);
        assertArrayEquals(data, original[0]);
    }

    private void encodeAndDecodeTestInt(byte[] data, String expected) {
        byte[] result = RLETCoder.ENCODER_INT.encodeRLET(data);
        assertEquals(HEX.formatHex(result), expected);
        byte[][] original = RLETCoder.ENCODER_INT.decodeRLET(result);
        assertArrayEquals(data, original[0]);
    }

    @Test
    public void testReadingContainer() {
        String s =
                "b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jYmMAAAAGYmNyeXB0AAAAGAAAABAMI6J9elHWflqdA5w3HO0jAAAAQAAAAAEAAAAAAAAAkEzlhHSSVjdSs45PBdGOVLxFtwybaZ4KIccgFuZvh5uXQYwxi1HtEDuEcr88w0MWw0VTdx06yaFOIOZ5WxMdDafaeWvZ+XFZfFxtVYAyKRBrIUJ0MojwdteQ+J/hM8hAjJoSquWTD+1q80hDtP+S7Wo8FfOB4eqKResOg55kgr1a7AMfnfGadlF8VAPImRsoVg==";
        OpenSSHKey key = OpenSSHKey.decode(s);
        System.out.println(key);
    }

    @Test
    public void test32KSize() {
        int size = 32768;
        byte[] data = new byte[size];
        encodeAndDecodeTestShort(data, "8000"+"00".repeat(size));
    }

    @Test
    public void test32KSizeInt() {
        int size = 32768;
        byte[] data = new byte[size];
        encodeAndDecodeTestInt(data, "00008000"+"00".repeat(size));
    }

    @Test
    public void test64KSize() {
        int size = 65535;
        byte[] data = new byte[size];
        encodeAndDecodeTestShort(data, "ffff" + "00".repeat(size));
    }

    @Test
    public void test64KSizeInt() {
        int size = 65535;
        byte[] data = new byte[size];
        encodeAndDecodeTestInt(data, "0000ffff" + "00".repeat(size));
    }
}
