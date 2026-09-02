package pro.deta.orion.command.resource;

import pro.deta.orion.auth.check.AccessDecision;

import java.util.Objects;
import java.util.Optional;

public record ScopedResourceCandidate<T>(
        String id,
        Optional<String> name,
        T value,
        AccessDecision accessDecision) {
    public ScopedResourceCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(accessDecision, "accessDecision");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (name.isPresent() && name.orElseThrow().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
    }
}
