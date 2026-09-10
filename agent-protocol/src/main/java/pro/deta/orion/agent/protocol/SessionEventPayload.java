package pro.deta.orion.agent.protocol;

import java.util.Objects;

public sealed interface SessionEventPayload permits SessionEventPayload.PtyOutput,
        SessionEventPayload.PtyInput, SessionEventPayload.PtyResize, SessionEventPayload.ProcessExited,
        SessionEventPayload.CommandResult, SessionEventPayload.HostWarning {

    record CommandResult(
            SessionCommandSource source,
            long operationSequence,
            ProtocolBytes sourceEnvelope,
            SessionCommandOutcome outcome,
            String detail
    ) implements SessionEventPayload {
        public CommandResult {
            Objects.requireNonNull(source, "source");
            if (operationSequence == 0 || operationSequence == -1) {
                throw new IllegalArgumentException("operationSequence must be between 1 and u64::MAX - 1");
            }
            Objects.requireNonNull(sourceEnvelope, "sourceEnvelope");
            if (sourceEnvelope.size() == 0) {
                throw new IllegalArgumentException("sourceEnvelope must not be empty");
            }
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detail, "detail");
            if (detail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
                throw new IllegalArgumentException("detail exceeds 4096 UTF-8 bytes");
            }
            if (outcome == SessionCommandOutcome.SUCCEEDED && !detail.isEmpty()) {
                throw new IllegalArgumentException("successful result detail must be empty");
            }
        }
    }

    record HostWarning(int code, String message) implements SessionEventPayload {
        private static final int MAX_MESSAGE_BYTES = 4096;

        public HostWarning {
            code = ProtocolValidation.unsignedShort(code, "code");
            if (code == 0) {
                throw new IllegalArgumentException("code must be nonzero");
            }
            Objects.requireNonNull(message, "message");
            int messageBytes;
            try {
                messageBytes = ProtocolValidation.utf8(message).length;
            } catch (AgentProtocolException exception) {
                throw new IllegalArgumentException("message must be valid Unicode", exception);
            }
            ProtocolValidation.byteLength(messageBytes, 1, MAX_MESSAGE_BYTES, "message");
        }
    }

    record PtyOutput(ProtocolBytes bytes) implements SessionEventPayload {
        public PtyOutput {
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    record PtyInput(String ptyInputId, ProtocolBytes bytes) implements SessionEventPayload {
        public PtyInput {
            ptyInputId = ProtocolValidation.identifier(ptyInputId, "ptyInputId");
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    record PtyResize(int columns, int rows) implements SessionEventPayload {
        public PtyResize {
            columns = ProtocolValidation.terminalDimension(columns, "columns");
            rows = ProtocolValidation.terminalDimension(rows, "rows");
        }
    }

    record ProcessExited(int exitCode) implements SessionEventPayload {
    }
}
