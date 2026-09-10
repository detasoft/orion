package pro.deta.orion.agentd.terminal;

import java.nio.charset.StandardCharsets;

final class TerminalDiagnostics {
    static final int MAX_BYTES = 512;

    private TerminalDiagnostics() {
    }

    static String bounded(String value) {
        String resolved = value == null ? "unknown failure" : value;
        byte[] encoded = resolved.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= MAX_BYTES) {
            return resolved;
        }
        int end = MAX_BYTES;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }

    static String detail(Throwable failure) {
        String message = failure.getMessage();
        return bounded(message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message);
    }
}
