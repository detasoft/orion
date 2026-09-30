package pro.deta.orion.git.proxy;

import java.util.Objects;
import java.util.Optional;

public record ResolvedBootstrapSource(
        String sourceId,
        String location,
        Optional<String> repositoryName,
        String refName,
        String path,
        Optional<String> revision,
        boolean createIfMissing) {
    public ResolvedBootstrapSource {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(repositoryName, "repositoryName");
        Objects.requireNonNull(refName, "refName");
        if (Objects.requireNonNull(path, "path").isBlank()) {
            throw new IllegalArgumentException("Bootstrap source path must not be empty");
        }
        Objects.requireNonNull(revision, "revision");
    }
}
