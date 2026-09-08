package pro.deta.orion.agentd.session;

import java.util.Objects;
import java.util.OptionalLong;

public sealed interface ControlResult {
    record Received(long operationSequence) implements ControlResult {
    }

    record Rejected(OptionalLong operationSequence, int errorCode, String detail) implements ControlResult {
        public Rejected {
            operationSequence = Objects.requireNonNull(operationSequence, "operationSequence");
            if (errorCode <= 0) {
                throw new IllegalArgumentException("errorCode must be positive");
            }
            Objects.requireNonNull(detail, "detail");
        }
    }

    record Status(HostStatus status) implements ControlResult {
        public Status {
            Objects.requireNonNull(status, "status");
        }
    }

    record Failed(OptionalLong operationSequence, FailureKind kind, String detail) implements ControlResult {
        public Failed {
            operationSequence = Objects.requireNonNull(operationSequence, "operationSequence");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }
    }

    enum FailureKind {
        VALIDATION,
        UNSUPPORTED_TRANSPORT,
        CONNECTION,
        TIMEOUT,
        FRAMING,
        AMBIGUOUS_DELIVERY
    }
}
