package pro.deta.orion.keymaterial.bootstrap;

import java.util.Objects;

/**
 * Outcome of atomically replacing the active runtime snapshot with a prepared candidate.
 */
public sealed interface MaterialBootstrapPublication
        permits MaterialBootstrapPublication.Published, MaterialBootstrapPublication.Failed {

    record Published() implements MaterialBootstrapPublication {
    }

    record Failed(MaterialBootstrapFailure failure) implements MaterialBootstrapPublication {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
