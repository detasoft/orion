package pro.deta.orion.keymaterial.bootstrap;

import java.util.Objects;

/**
 * Fully validated runtime candidate bound to the exact material and configuration revisions that produced it.
 */
public record MaterialBootstrapCandidate<T>(
        String materialRevision,
        String configurationRevision,
        T runtimeState) {

    public MaterialBootstrapCandidate {
        if (materialRevision == null || materialRevision.isBlank()) {
            throw new IllegalArgumentException("Material revision must not be empty");
        }
        if (configurationRevision == null || configurationRevision.isBlank()) {
            throw new IllegalArgumentException("Configuration revision must not be empty");
        }
        Objects.requireNonNull(runtimeState, "runtimeState");
    }
}
