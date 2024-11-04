package pro.deta.orion.util;

import java.nio.ByteBuffer;

public class ByteBufferUtil {
    public static String toHex(ByteBuffer data, int limit) {
        StringBuilder result = new StringBuilder();
        int counter = 0;
        int b;
        while (data.hasRemaining()) {
            if (counter % 16 == 0)
                result.append(String.format("%04X: ", counter));

            b = data.get() & 0xff;
            result.append(String.format("%02X ", b));

            counter++;
            if (counter % 16 == 0) {
                result.append("  ");
                toText(data, result, 16);
                result.append("\n");
            }
            if (limit != 0 && counter > limit) {
                result.append("(more...)");
                break;
            }
        }
        int rest = counter % 16;
        if (rest > 0) {
            for (int i = 0; i < 17 - rest; i++) {
                result.append("   ");
            }
            toText(data, result, rest);
        }
        return result.toString();
    }

    /**
     * Gets last <tt>cnt</tt> read bytes from the <tt>data</tt> buffer and puts into <tt>result</tt> buffer in special
     * format:
     * <ul>
     * <li>if byte represents char from partition 0x1F to 0x80 (which are normal ascii chars) then it's put into buffer as
     * it is</li>
     * <li>otherwise dot is put into buffer</li>
     * </ul>
     *
     */
    private static void toText(ByteBuffer data, StringBuilder result, int cnt) {
        int charPos = data.position() - cnt;
        for (int a = 0; a < cnt; a++) {
            int c = data.get(charPos++);
            if (c > 0x1f && c < 0x80)
                result.append((char) c);
            else
                result.append('.');
        }
    }
}
