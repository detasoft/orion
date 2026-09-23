package pro.deta.orion.git.nativestorage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GitRepositoryFileSnapshot(Map<String, GitFile> files, Optional<String> version) {
    public GitRepositoryFileSnapshot {
        Objects.requireNonNull(files, "files");
        Map<String, GitFile> copy = new LinkedHashMap<>();
        for (Map.Entry<String, GitFile> entry : files.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "file path"),
                    Objects.requireNonNull(entry.getValue(), "file"));
        }
        files = Collections.unmodifiableMap(copy);
        version = Objects.requireNonNullElseGet(version, Optional::empty);
    }
}
