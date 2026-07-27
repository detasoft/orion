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
        UNEXPECTED_PACKET,
        INVALID_INITIAL_SERVICE_REQUEST,
        INVALID_PROTOCOL_V2_REQUEST,
        INVALID_PROTOCOL_V2_RESPONSE,
        INVALID_ADVERTISEMENT,
        INVALID_PHASE_TRANSITION,
        MISSING_UNPACK_STATUS,
        DUPLICATE_UNPACK_STATUS,
        INVALID_REPORT_STATUS_LINE,
        INVALID_SIDE_BAND,
        SIDE_BAND_FATAL,
        INVALID_RECEIVE_PACK_COMMAND
    }

    public enum Phase {
        CONTROL_HEADER,
        STRUCTURED_PAYLOAD,
        RAW_STREAM,
        SIDE_BAND,
        ADVERTISEMENT,
        LS_REFS_RESPONSE,
        FETCH_RESPONSE
    }
}
