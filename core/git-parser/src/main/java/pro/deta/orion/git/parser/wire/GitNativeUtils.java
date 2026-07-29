package pro.deta.orion.git.parser.wire;

import java.util.Arrays;

public final class GitNativeUtils {

    public static final int[] HEX_VALUES = hexValues();

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
