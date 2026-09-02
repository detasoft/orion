package pro.deta.orion.keymaterial.bootstrap;

import java.util.Objects;

/**
 * Result of loading and independently validating one side of the bootstrap barrier.
 */
public sealed interface MaterialBootstrapLoadResult<T>
        permits MaterialBootstrapLoadResult.Loaded, MaterialBootstrapLoadResult.Failed {

    record Loaded<T>(MaterialBootstrapInput<T> input) implements MaterialBootstrapLoadResult<T> {
        public Loaded {
            Objects.requireNonNull(input, "input");
        }
    }

    record Failed<T>(MaterialBootstrapFailure failure) implements MaterialBootstrapLoadResult<T> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
