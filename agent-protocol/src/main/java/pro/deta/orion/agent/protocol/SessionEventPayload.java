package pro.deta.orion.agent.protocol;

import java.util.Objects;

public sealed interface SessionEventPayload permits SessionEventPayload.PtyOutput,
        SessionEventPayload.PtyInput, SessionEventPayload.PtyResize, SessionEventPayload.ProcessExited {

    record PtyOutput(ProtocolBytes bytes) implements SessionEventPayload {
        public PtyOutput {
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    record PtyInput(CommandId commandId, ProtocolBytes bytes) implements SessionEventPayload {
        public PtyInput {
            Objects.requireNonNull(commandId, "commandId");
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
