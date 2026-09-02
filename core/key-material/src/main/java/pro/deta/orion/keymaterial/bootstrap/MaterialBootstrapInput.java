package pro.deta.orion.keymaterial.bootstrap;

import java.util.Objects;

/**
 * Independently loaded bootstrap input identified by its durable source revision.
 */
public record MaterialBootstrapInput<T>(String revision, T value) {
    public MaterialBootstrapInput {
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException("Material bootstrap input revision must not be empty");
        }
        Objects.requireNonNull(value, "value");
    }
}
