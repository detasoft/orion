package pro.deta.orion.resource.reference.resolver;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public record GitRepositoryLocation(Kind kind, String location, Map<String, String> parameters) {
    public enum Kind {
        LOCAL,
        SSH,
        HTTP,
        HTTPS,
        S3,
        UNKNOWN
    }

    public GitRepositoryLocation {
        if (kind == null) {
            throw new IllegalArgumentException("Git repository kind must not be null");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Git repository location must not be empty");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public Optional<Path> localPath() {
        if (kind != Kind.LOCAL) {
            return Optional.empty();
        }
        return Optional.of(Path.of(location));
    }

    public Optional<String> ref() {
        String value = parameters.get("ref");
        if (value == null || value.isBlank()) {
            value = parameters.get("branch");
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
