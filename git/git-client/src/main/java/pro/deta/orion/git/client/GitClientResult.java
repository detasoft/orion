package pro.deta.orion.git.client;

import java.util.Objects;

public sealed interface GitClientResult<T>
        permits GitClientResult.Success, GitClientResult.Failed {

    record Success<T>(T value) implements GitClientResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failed<T>(GitClientFailure failure) implements GitClientResult<T> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
