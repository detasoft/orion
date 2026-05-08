package pro.deta.orion.git.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GitFileSnapshot(Map<String, byte[]> files, Optional<String> version) {
    public GitFileSnapshot {
        Objects.requireNonNull(files, "files");
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "file path"),
                    Objects.requireNonNull(entry.getValue(), "file content").clone());
        }
        files = Collections.unmodifiableMap(copy);
        version = Objects.requireNonNullElseGet(version, Optional::empty);
    }

    @Override
    public Map<String, byte[]> files() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return copy;
    }
}
