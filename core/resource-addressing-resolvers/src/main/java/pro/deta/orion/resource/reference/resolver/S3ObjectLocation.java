package pro.deta.orion.resource.reference.resolver;

import java.util.Map;
import java.util.Optional;

public record S3ObjectLocation(String bucket, String key, Map<String, String> parameters) {
    public S3ObjectLocation {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 bucket must not be empty");
        }
        if (key == null) {
            throw new IllegalArgumentException("S3 key must not be null");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public Optional<String> region() {
        String value = parameters.get("region");
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
