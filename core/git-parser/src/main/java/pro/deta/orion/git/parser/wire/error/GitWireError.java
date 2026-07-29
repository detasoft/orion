package pro.deta.orion.git.parser.wire.error;

import java.util.Objects;

public record GitWireError(
        Kind kind,
        Phase phase) {
    public static final long UNKNOWN_INDEX = -1;

    public GitWireError {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
    }

    public enum Kind {
        INVALID_HEX_HEADER("Pkt-line length contains non-hex byte"),
        RESERVED_LENGTH("Pkt-line length 0003 is reserved"),
        LENGTH_EXCEEDS_LIMIT("Pkt-line length exceeds Git pkt-line limit"),
        PKT_LINE_HEADER_PARSE_FAILURE("Failed to parse Git pkt-line header"),
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
        INVALID_RECEIVE_PACK_COMMAND;

        private String message;

        Kind(String message) {
            this.message = message;
        }
        Kind() {
            this.message = "error";
        }

        public String getMessage() {
            return message;
        }
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
