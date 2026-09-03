package pro.deta.orion.agent.protocol;

import java.util.Objects;

public sealed interface SequenceDecodeIssue
        permits SequenceDecodeIssue.Recoverable, SequenceDecodeIssue.Terminal {

    AgentProtocolException exception();

    record Recoverable(AgentProtocolException exception, int encodedLength) implements SequenceDecodeIssue {
        public Recoverable {
            Objects.requireNonNull(exception, "exception");
            if (encodedLength < 1) {
                throw new IllegalArgumentException("encodedLength must be positive");
            }
        }
    }

    record Terminal(AgentProtocolException exception, int pendingBytes) implements SequenceDecodeIssue {
        public Terminal {
            Objects.requireNonNull(exception, "exception");
            if (pendingBytes < 0) {
                throw new IllegalArgumentException("pendingBytes must not be negative");
            }
        }
    }
}
