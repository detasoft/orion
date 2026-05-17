package pro.deta.orion.resource.address;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

public record ResourceContent(String sourceName, byte[] bytes, Optional<String> version) {
    public ResourceContent(String sourceName, byte[] bytes) {
        this(sourceName, bytes, Optional.empty());
    }

    public ResourceContent(String sourceName, byte[] bytes, String version) {
        this(sourceName, bytes, version == null ? Optional.empty() : Optional.of(version));
    }

    public ResourceContent {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Resource source name must not be empty");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("Resource bytes must not be null");
        }
        if (version == null) {
            version = Optional.empty();
        }
        if (version.isPresent() && version.get().isBlank()) {
            throw new IllegalArgumentException("Resource content version must not be empty");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public String asUtf8String() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
