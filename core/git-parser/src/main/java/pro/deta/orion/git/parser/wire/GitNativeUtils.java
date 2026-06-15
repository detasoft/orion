package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.Arrays;
import java.util.Objects;

public final class GitNativeUtils {
    private static final int[] HEX_VALUES = hexValues();

    private GitNativeUtils() {
    }

    public static int packetLength(ByteBuf input, int headerIndex) {
        Objects.requireNonNull(input, "input");

        int header = input.getInt(headerIndex);
        int h0 = HEX_VALUES[(header >>> 24) & 0xff];
        int h1 = HEX_VALUES[(header >>> 16) & 0xff];
        int h2 = HEX_VALUES[(header >>> 8) & 0xff];
        int h3 = HEX_VALUES[header & 0xff];
        if ((h0 | h1 | h2 | h3) < 0) {
            throw new IllegalArgumentException("Pkt-line length contains non-hex byte");
        }
        return (h0 << 12) | (h1 << 8) | (h2 << 4) | h3;
    }

    private static int[] hexValues() {
        int[] values = new int[256];
        Arrays.fill(values, -1);
        for (int i = '0'; i <= '9'; i++) {
            values[i] = i - '0';
        }
        for (int i = 'a'; i <= 'f'; i++) {
            values[i] = i - 'a' + 10;
        }
        for (int i = 'A'; i <= 'F'; i++) {
            values[i] = i - 'A' + 10;
        }
        return values;
    }
}
