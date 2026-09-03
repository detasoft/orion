package pro.deta.orion.git.proxy;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

public record ResolvedBootstrapSource(
        String sourceId,
        String location,
        Optional<String> repositoryName,
        String refName,
        List<String> paths,
        Optional<String> revision,
        boolean createIfMissing) {
    public ResolvedBootstrapSource {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(repositoryName, "repositoryName");
        Objects.requireNonNull(refName, "refName");
        paths = List.copyOf(paths);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Bootstrap source paths must not be empty");
        }
        Objects.requireNonNull(revision, "revision");
    }

    public String path() {
        return paths.getFirst();
    }
}
