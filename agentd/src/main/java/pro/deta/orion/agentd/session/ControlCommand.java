package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public sealed interface ControlCommand {
    default OptionalLong operationSequence() {
        return OptionalLong.empty();
    }

    record Input(
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            UUID inputId,
            ProtocolBytes bytes
    ) implements ControlCommand {
        public Input {
            serverCommandEnvelope = requireOperation(sequence, source, serverCommandEnvelope);
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public OptionalLong operationSequence() {
            return OptionalLong.of(sequence);
        }
    }

    record Resize(
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            int columns,
            int rows
    ) implements ControlCommand {
        public Resize {
            serverCommandEnvelope = requireOperation(sequence, source, serverCommandEnvelope);
            requireDimension(columns, "columns");
            requireDimension(rows, "rows");
        }

        @Override
        public OptionalLong operationSequence() {
            return OptionalLong.of(sequence);
        }
    }

    record Signal(
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            AgentMessage.SignalKind kind,
            int platformCode,
            OptionalLong processToken
    ) implements ControlCommand {
        public Signal {
            serverCommandEnvelope = requireOperation(sequence, source, serverCommandEnvelope);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(processToken, "processToken");
            if (processToken.isPresent() && processToken.getAsLong() == 0) {
                throw new IllegalArgumentException("processToken must be nonzero");
            }
            if (kind == AgentMessage.SignalKind.PLATFORM && platformCode < 0) {
                throw new IllegalArgumentException("platform signal requires a non-negative code");
            }
            if (kind != AgentMessage.SignalKind.PLATFORM && platformCode < -1) {
                throw new IllegalArgumentException("portable signal platform code must be -1 or non-negative");
            }
        }

        @Override
        public OptionalLong operationSequence() {
            return OptionalLong.of(sequence);
        }
    }

    record Terminate(
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            AgentMessage.TerminationMode mode
    ) implements ControlCommand {
        public Terminate {
            serverCommandEnvelope = requireOperation(sequence, source, serverCommandEnvelope);
            Objects.requireNonNull(mode, "mode");
        }

        @Override
        public OptionalLong operationSequence() {
            return OptionalLong.of(sequence);
        }
    }

    record AckJournal(
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope,
            long acknowledgedEventId
    ) implements ControlCommand {
        public AckJournal {
            serverCommandEnvelope = requireOperation(sequence, source, serverCommandEnvelope);
            if (acknowledgedEventId == 0) {
                throw new IllegalArgumentException("acknowledgedEventId must be non-zero");
            }
        }

        @Override
        public OptionalLong operationSequence() {
            return OptionalLong.of(sequence);
        }
    }

    record Status() implements ControlCommand {
    }

    record ListProcesses() implements ControlCommand {
    }

    private static Optional<ProtocolBytes> requireOperation(
            long sequence,
            SessionCommandSource source,
            Optional<ProtocolBytes> serverCommandEnvelope
    ) {
        if (sequence == 0 || sequence == -1) {
            throw new IllegalArgumentException("operation sequence must be between 1 and u64::MAX - 1");
        }
        Objects.requireNonNull(source, "source");
        serverCommandEnvelope = Objects.requireNonNull(serverCommandEnvelope, "serverCommandEnvelope");
        if (source == SessionCommandSource.SERVER
                && (serverCommandEnvelope.isEmpty() || serverCommandEnvelope.orElseThrow().size() == 0)) {
            throw new IllegalArgumentException("SERVER operation requires a nonempty server command envelope");
        }
        if (source == SessionCommandSource.MANUAL && serverCommandEnvelope.isPresent()) {
            throw new IllegalArgumentException("MANUAL operation must not contain a server command envelope");
        }
        return serverCommandEnvelope;
    }

    private static void requireDimension(int value, String name) {
        if (value < 1 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }
}
