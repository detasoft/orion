package pro.deta.orion.transport.git.command.read;

import java.util.List;
import java.util.Objects;

public sealed interface OperatorQueryResult<T> {
    interface ScalarValue {}

    record AvailableValue<T extends ScalarValue>(T value) implements OperatorQueryResult<T> {
        public AvailableValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "AvailableValue";
        }
    }

    record AvailableSnapshot<T>(List<T> value) implements OperatorQueryResult<List<T>> {
        public AvailableSnapshot {
            Objects.requireNonNull(value, "value");
            value = List.copyOf(value);
        }

        @Override
        public String toString() {
            return "AvailableSnapshot";
        }
    }

    record Unavailable<T>(String source) implements OperatorQueryResult<T> {
        public Unavailable {
            source = requireSource(source);
        }
    }

    record Failed<T>(String source, Throwable cause) implements OperatorQueryResult<T> {
        public Failed {
            source = requireSource(source);
            Objects.requireNonNull(cause, "cause");
        }

        @Override
        public String toString() {
            return "Failed[source=" + source + "]";
        }
    }

    private static String requireSource(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return source;
    }
}
