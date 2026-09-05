package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public sealed interface ControlCommand {
    default Optional<CommandId> commandId() {
        return Optional.empty();
    }

    record Input(
            CommandId id,
            long operationSequence,
            ProtocolBytes commandEnvelope,
            UUID inputId,
            ProtocolBytes bytes
    ) implements ControlCommand {
        public Input {
            Objects.requireNonNull(id, "id");
            requireOperationSequence(operationSequence);
            Objects.requireNonNull(commandEnvelope, "commandEnvelope");
            if (commandEnvelope.toByteArray().length == 0) {
                throw new IllegalArgumentException("command envelope must not be empty");
            }
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public Optional<CommandId> commandId() {
            return Optional.of(id);
        }
    }

    record Resize(
            CommandId id,
            long operationSequence,
            ProtocolBytes commandEnvelope,
            int columns,
            int rows
    ) implements ControlCommand {
        public Resize {
            Objects.requireNonNull(id, "id");
            requireOperationSequence(operationSequence);
            Objects.requireNonNull(commandEnvelope, "commandEnvelope");
            if (commandEnvelope.toByteArray().length == 0) {
                throw new IllegalArgumentException("command envelope must not be empty");
            }
            requireDimension(columns, "columns");
            requireDimension(rows, "rows");
        }

        @Override
        public Optional<CommandId> commandId() {
            return Optional.of(id);
        }
    }

    record Signal(
            CommandId id,
            long operationSequence,
            ProtocolBytes commandEnvelope,
            AgentMessage.SignalKind kind,
            int platformCode
    ) implements ControlCommand {
        public Signal {
            Objects.requireNonNull(id, "id");
            requireOperationSequence(operationSequence);
            Objects.requireNonNull(commandEnvelope, "commandEnvelope");
            if (commandEnvelope.toByteArray().length == 0) {
                throw new IllegalArgumentException("command envelope must not be empty");
            }
            Objects.requireNonNull(kind, "kind");
            if (kind == AgentMessage.SignalKind.PLATFORM && platformCode < 0) {
                throw new IllegalArgumentException("platform signal requires a non-negative code");
            }
            if (kind != AgentMessage.SignalKind.PLATFORM && platformCode != -1) {
                throw new IllegalArgumentException("portable signals require platform code -1");
            }
        }

        @Override
        public Optional<CommandId> commandId() {
            return Optional.of(id);
        }
    }

    record Terminate(
            CommandId id,
            long operationSequence,
            ProtocolBytes commandEnvelope,
            AgentMessage.TerminationMode mode,
            long graceMillis
    ) implements ControlCommand {
        public Terminate {
            Objects.requireNonNull(id, "id");
            requireOperationSequence(operationSequence);
            Objects.requireNonNull(commandEnvelope, "commandEnvelope");
            if (commandEnvelope.toByteArray().length == 0) {
                throw new IllegalArgumentException("command envelope must not be empty");
            }
            Objects.requireNonNull(mode, "mode");
            if (graceMillis < 0 || graceMillis > 0xffff_ffffL) {
                throw new IllegalArgumentException("graceMillis must fit an unsigned 32-bit integer");
            }
        }

        @Override
        public Optional<CommandId> commandId() {
            return Optional.of(id);
        }
    }

    record Status() implements ControlCommand {
    }

    private static void requireOperationSequence(long operationSequence) {
        if (operationSequence == 0) {
            throw new IllegalArgumentException("operationSequence must be non-zero");
        }
    }

    private static void requireDimension(int value, String name) {
        if (value < 1 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }
}
