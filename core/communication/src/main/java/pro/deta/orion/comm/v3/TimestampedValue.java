package pro.deta.orion.comm.v3;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * @param instant milliseconds since epoch
 */
public record TimestampedValue<T>(T value, Instant instant) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    public TimestampedValue(T value) {
        this(value, Instant.now());
        assert value != null;
    }

    public String format() {
        return FORMATTER.format(instant);
    }
}