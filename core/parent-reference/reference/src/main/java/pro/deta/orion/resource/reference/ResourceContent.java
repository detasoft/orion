package pro.deta.orion.resource.reference;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record ResourceContent(String sourceName, byte[] bytes) {
    public ResourceContent {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Resource source name must not be empty");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("Resource bytes must not be null");
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
