package pro.deta.orion.config;

import java.util.Objects;
import java.util.Optional;

public record ConfigurationFile(byte[] content, Optional<String> revision) {
    public ConfigurationFile {
        content = Objects.requireNonNull(content, "content").clone();
        Objects.requireNonNull(revision, "revision");
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
