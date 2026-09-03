package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.CommandId;

import java.util.Objects;
import java.util.Optional;

public sealed interface ControlResult {
    record Acknowledged(CommandId commandId, boolean duplicate, long journalTimestamp)
            implements ControlResult {
        public Acknowledged {
            Objects.requireNonNull(commandId, "commandId");
            if (journalTimestamp < 0) {
                throw new IllegalArgumentException("journalTimestamp must fit a signed positive long");
            }
        }
    }

    record Rejected(Optional<CommandId> commandId, int errorCode, String detail) implements ControlResult {
        public Rejected {
            commandId = Objects.requireNonNull(commandId, "commandId");
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

    record Failed(Optional<CommandId> commandId, FailureKind kind, String detail) implements ControlResult {
        public Failed {
            commandId = Objects.requireNonNull(commandId, "commandId");
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
