package pro.deta.orion.agent.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SequenceDecodeResult<T>(
        List<Outcome<T>> outcomes,
        Optional<SequenceDecodeIssue.Terminal> terminalIssue
) {
    public SequenceDecodeResult {
        outcomes = List.copyOf(outcomes);
        for (Outcome<T> outcome : outcomes) {
            Objects.requireNonNull(outcome, "outcomes contains null");
        }
        terminalIssue = Objects.requireNonNull(terminalIssue, "terminalIssue");
    }

    public sealed interface Outcome<T> permits Decoded, Rejected {
    }

    public record Decoded<T>(T value) implements Outcome<T> {
        public Decoded {
            Objects.requireNonNull(value, "value");
        }
    }

    public record Rejected<T>(SequenceDecodeIssue.Recoverable issue) implements Outcome<T> {
        public Rejected {
            Objects.requireNonNull(issue, "issue");
        }
    }
}
