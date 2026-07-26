package pro.deta.orion.git.parser.wire;

import java.util.Objects;

public record GitWireError(
        Kind kind,
        Phase phase,
        long packetIndex,
        long byteOffset,
        String message) {
    public static final long UNKNOWN_INDEX = -1;

    public GitWireError {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(message, "message");
    }

    public enum Kind {
        INVALID_HEX_HEADER,
        RESERVED_LENGTH,
        LENGTH_EXCEEDS_LIMIT,
        INCOMPLETE_HEADER,
        INCOMPLETE_PAYLOAD,
        INVALID_SIDE_BAND,
        SIDE_BAND_FATAL
    }

    public enum Phase {
        CONTROL_HEADER,
        STRUCTURED_PAYLOAD,
        RAW_STREAM,
        SIDE_BAND
    }
}
