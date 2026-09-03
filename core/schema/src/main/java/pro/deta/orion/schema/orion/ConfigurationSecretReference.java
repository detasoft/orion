package pro.deta.orion.schema.orion;

import java.util.Objects;

public record ConfigurationSecretReference(Scope scope, String reference) {
    public ConfigurationSecretReference {
        Objects.requireNonNull(scope, "secret reference scope");
        reference = IdentifierRules.requireCanonical(reference, "secret reference");
    }

    public enum Scope {
        ORGANIZATION,
        REPOSITORY
    }
}
