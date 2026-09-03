package pro.deta.orion.agentd.sandbox;

import java.util.HashMap;
import java.util.Map;

public enum LandlockRight {
    EXECUTE("execute", 1L << 0, false),
    WRITE_FILE("write-file", 1L << 1, false),
    READ_FILE("read-file", 1L << 2, false),
    READ_DIR("read-dir", 1L << 3, true),
    REMOVE_DIR("remove-dir", 1L << 4, true),
    REMOVE_FILE("remove-file", 1L << 5, true),
    MAKE_CHAR("make-char", 1L << 6, true),
    MAKE_DIR("make-dir", 1L << 7, true),
    MAKE_REG("make-reg", 1L << 8, true),
    MAKE_SOCK("make-sock", 1L << 9, true),
    MAKE_FIFO("make-fifo", 1L << 10, true),
    MAKE_BLOCK("make-block", 1L << 11, true),
    MAKE_SYM("make-sym", 1L << 12, true),
    REFER("refer", 1L << 13, true),
    TRUNCATE("truncate", 1L << 14, false),
    IOCTL_DEV("ioctl-dev", 1L << 15, false),
    RESOLVE_UNIX("resolve-unix", 1L << 16, false);

    public static final long HANDLED_MASK = (1L << 17) - 1;
    private static final Map<String, LandlockRight> BY_TOKEN = tokens();

    private final String token;
    private final long mask;
    private final boolean directoryOperation;

    LandlockRight(String token, long mask, boolean directoryOperation) {
        this.token = token;
        this.mask = mask;
        this.directoryOperation = directoryOperation;
    }

    public String token() {
        return token;
    }

    public long mask() {
        return mask;
    }

    public boolean directoryOperation() {
        return directoryOperation;
    }

    static LandlockRight forToken(String token) {
        return BY_TOKEN.get(token);
    }

    private static Map<String, LandlockRight> tokens() {
        Map<String, LandlockRight> values = new HashMap<>();
        for (LandlockRight right : values()) {
            values.put(right.token, right);
        }
        return Map.copyOf(values);
    }
}
