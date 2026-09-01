package pro.deta.orion.git.client;

import java.time.Duration;
import java.util.Objects;

public record GitClientOptions(
        Duration connectTimeout,
        Duration readTimeout,
        Duration writeTimeout,
        Duration operationTimeout,
        long maximumPackBytes) {

    public GitClientOptions {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(writeTimeout, "writeTimeout");
        requirePositive(operationTimeout, "operationTimeout");
        if (operationTimeout.compareTo(connectTimeout) < 0
                || operationTimeout.compareTo(readTimeout) < 0
                || operationTimeout.compareTo(writeTimeout) < 0) {
            throw new IllegalArgumentException(
                    "operationTimeout must cover each individual timeout");
        }
        if (maximumPackBytes <= 0) {
            throw new IllegalArgumentException("maximumPackBytes must be positive");
        }
    }

    public static GitClientOptions defaults() {
        return new GitClientOptions(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                4L * 1024 * 1024 * 1024);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
