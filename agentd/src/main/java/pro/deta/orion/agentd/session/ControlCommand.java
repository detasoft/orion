package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ProtocolBytes;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public sealed interface ControlCommand {
    default OptionalLong operationSequence() {
        return OptionalLong.empty();
    }

    record Input(
            long sequence,
            ProtocolBytes commandEnvelope,
            UUID inputId,
            ProtocolBytes bytes
    ) implements ControlCommand {
        public Input {
            requireOperation(sequence, commandEnvelope);
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
            ProtocolBytes commandEnvelope,
            int columns,
            int rows
    ) implements ControlCommand {
        public Resize {
            requireOperation(sequence, commandEnvelope);
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
            ProtocolBytes commandEnvelope,
            AgentMessage.SignalKind kind,
            int platformCode
    ) implements ControlCommand {
        public Signal {
            requireOperation(sequence, commandEnvelope);
            Objects.requireNonNull(kind, "kind");
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
            ProtocolBytes commandEnvelope,
            AgentMessage.TerminationMode mode
    ) implements ControlCommand {
        public Terminate {
            requireOperation(sequence, commandEnvelope);
            Objects.requireNonNull(mode, "mode");
        }

        @Override
        public OptionalLong operationSequence() {
            return OptionalLong.of(sequence);
        }
    }

    record AckJournal(
            long sequence,
            ProtocolBytes commandEnvelope,
            long acknowledgedEventId
    ) implements ControlCommand {
        public AckJournal {
            requireOperation(sequence, commandEnvelope);
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

    private static void requireOperation(long sequence, ProtocolBytes commandEnvelope) {
        if (sequence == 0 || sequence == -1) {
            throw new IllegalArgumentException("operation sequence must be between 1 and u64::MAX - 1");
        }
        Objects.requireNonNull(commandEnvelope, "commandEnvelope");
        if (commandEnvelope.toByteArray().length == 0) {
            throw new IllegalArgumentException("command envelope must not be empty");
        }
    }

    private static void requireDimension(int value, String name) {
        if (value < 1 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }
}
