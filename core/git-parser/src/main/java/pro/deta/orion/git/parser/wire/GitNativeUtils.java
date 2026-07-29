package pro.deta.orion.git.parser.wire;

import java.util.Arrays;

public final class GitNativeUtils {

    public static final int[] HEX_VALUES = hexValues();
    private static final byte[] HEX_DIGITS = new byte[]{
            '0', '1', '2', '3',
            '4', '5', '6', '7',
            '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f'
    };

    private GitNativeUtils() {
    }

    public static byte hexDigit(int value) {
        if (value < 0 || value >= HEX_DIGITS.length) {
            throw new IllegalArgumentException(
                    "Hex digit value must be between 0 and 15");
        }
        return HEX_DIGITS[value];
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
