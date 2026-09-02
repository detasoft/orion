package pro.deta.orion.keymaterial.bootstrap;

import java.util.List;
import java.util.Objects;

/**
 * Terminal result of one bootstrap or reload attempt.
 */
public sealed interface MaterialBootstrapResult<T>
        permits MaterialBootstrapResult.Activated, MaterialBootstrapResult.Failed {

    record Activated<T>(MaterialBootstrapCandidate<T> candidate) implements MaterialBootstrapResult<T> {
        public Activated {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    record Failed<T>(List<MaterialBootstrapFailure> failures) implements MaterialBootstrapResult<T> {
        public Failed {
            Objects.requireNonNull(failures, "failures");
            failures = List.copyOf(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Material bootstrap failures must not be empty");
            }
        }
    }
}
