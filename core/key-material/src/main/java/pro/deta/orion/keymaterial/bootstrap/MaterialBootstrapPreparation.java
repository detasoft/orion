package pro.deta.orion.keymaterial.bootstrap;

import java.util.Objects;

/**
 * Result of decrypting, cross-validating, and projecting a loaded input pair without publishing it.
 */
public sealed interface MaterialBootstrapPreparation<T>
        permits MaterialBootstrapPreparation.Ready, MaterialBootstrapPreparation.Failed {

    record Ready<T>(T runtimeState) implements MaterialBootstrapPreparation<T> {
        public Ready {
            Objects.requireNonNull(runtimeState, "runtimeState");
        }
    }

    record Failed<T>(MaterialBootstrapFailure failure) implements MaterialBootstrapPreparation<T> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
