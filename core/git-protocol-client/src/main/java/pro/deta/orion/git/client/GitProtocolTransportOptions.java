package pro.deta.orion.git.client;

import java.time.Duration;
import java.util.Objects;

public record GitProtocolTransportOptions(
        Duration connectTimeout,
        Duration readTimeout,
        Duration writeTimeout,
        Duration operationTimeout,
        int maximumPacketBytes,
        long maximumPackBytes) {
    private static final int PKT_LINE_HEADER_BYTES = 4;

    public GitProtocolTransportOptions {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(writeTimeout, "writeTimeout");
        requirePositive(operationTimeout, "operationTimeout");
        if (operationTimeout.compareTo(connectTimeout) < 0
                || operationTimeout.compareTo(readTimeout) < 0
                || operationTimeout.compareTo(writeTimeout) < 0) {
            throw new IllegalArgumentException("operationTimeout must cover each individual timeout");
        }
        if (maximumPacketBytes < PKT_LINE_HEADER_BYTES) {
            throw new IllegalArgumentException("maximumPacketBytes must include a pkt-line header");
        }
        if (maximumPackBytes <= 0) {
            throw new IllegalArgumentException("maximumPackBytes must be positive");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
