package pro.deta.orion.agentd.session;

import java.util.List;
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

    record Processes(List<Process> processes) implements ControlResult {
        public Processes {
            processes = List.copyOf(processes);
        }
    }

    record ServerControlClaimed(
            OptionalLong acceptedServerSequenceHighWatermark,
            OptionalLong acknowledgedJournalEventId
    ) implements ControlResult {
        public ServerControlClaimed {
            acceptedServerSequenceHighWatermark = Objects.requireNonNull(
                    acceptedServerSequenceHighWatermark, "acceptedServerSequenceHighWatermark");
            acknowledgedJournalEventId = Objects.requireNonNull(
                    acknowledgedJournalEventId, "acknowledgedJournalEventId");
            if (acceptedServerSequenceHighWatermark.isPresent()
                    && (acceptedServerSequenceHighWatermark.getAsLong() == 0
                    || acceptedServerSequenceHighWatermark.getAsLong() == -1)) {
                throw new IllegalArgumentException(
                        "accepted server sequence high-watermark must be between 1 and u64::MAX - 1");
            }
            if (acknowledgedJournalEventId.isPresent()
                    && acknowledgedJournalEventId.getAsLong() == 0) {
                throw new IllegalArgumentException("acknowledged journal event ID must be non-zero");
            }
        }
    }

    record Process(long token, long pid, boolean originalRoot) {
        public Process {
            if (token == 0 || pid <= 0 || pid > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid process identity");
            }
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
