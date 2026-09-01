package pro.deta.orion.git.client;

import java.util.Objects;

public record GitClientFailure(
        Kind kind,
        Phase phase,
        boolean retryable,
        String message,
        Throwable cause) {

    public GitClientFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public enum Kind {
        TRANSPORT_UNAVAILABLE,
        AUTHENTICATION_FAILED,
        AUTHORIZATION_DENIED,
        VERIFICATION_FAILED,
        PROTOCOL_UNSUPPORTED,
        CAPABILITY_MISSING,
        MALFORMED_RESPONSE,
        SERVER_ERROR,
        SIDE_BAND_ERROR,
        UNEXPECTED_END_OF_STREAM,
        PACK_SIZE_LIMIT_EXCEEDED,
        REMOTE_REF_REJECTED,
        REMOTE_UNPACK_FAILED,
        TIMEOUT,
        CANCELLED
    }

    public enum Phase {
        OPEN,
        ADVERTISEMENT,
        NEGOTIATION,
        PACK_TRANSFER,
        REPORT_STATUS,
        CLOSE
    }
}
