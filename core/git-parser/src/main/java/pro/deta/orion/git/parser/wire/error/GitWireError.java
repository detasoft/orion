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
        INVALID_RECEIVE_PACK_COMMAND,
        EMPTY_LEGACY_UPLOAD_PACKET(
                "Legacy upload request contains an empty packet"),
        UNSUPPORTED_LEGACY_UPLOAD_CONTROL(
                "Control packet is not supported in a legacy upload request"),
        MISSING_LEGACY_UPLOAD_WANT(
                "Legacy upload request ended before the first want"),
        UNSUPPORTED_LEGACY_UPLOAD_COMMAND(
                "Legacy upload request contains an unsupported command"),
        INVALID_LEGACY_UPLOAD_OBJECT_ID(
                "Legacy upload want must contain a 40-digit hexadecimal object ID"),
        LATE_LEGACY_UPLOAD_CAPABILITIES(
                "Legacy upload capabilities are allowed only on the first want"),
        EMPTY_LEGACY_UPLOAD_CAPABILITY(
                "Legacy upload capability must not be empty"),
        INVALID_LEGACY_UPLOAD_REQUEST(
                "Failed to read legacy upload-pack request"),
        EMPTY_LEGACY_UPLOAD_NEGOTIATION_PACKET(
                "Legacy upload negotiation contains an empty packet"),
        UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_CONTROL(
                "Control packet is not supported in legacy upload negotiation"),
        UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_COMMAND(
                "Legacy upload negotiation contains an unsupported command"),
        INVALID_LEGACY_UPLOAD_HAVE_OBJECT_ID(
                "Legacy upload have must contain a 40-digit hexadecimal object ID"),
        INVALID_LEGACY_UPLOAD_NEGOTIATION(
                "Failed to read legacy upload-pack negotiation");

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
