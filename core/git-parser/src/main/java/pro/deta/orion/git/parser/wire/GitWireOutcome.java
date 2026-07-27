package pro.deta.orion.git.parser.wire;

import java.util.Objects;

public sealed interface GitWireOutcome<T>
        permits GitWireOutcome.Success, GitWireOutcome.Failure {

    record Success<T>(T value) implements GitWireOutcome<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(GitWireFailure failure) implements GitWireOutcome<T> {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
