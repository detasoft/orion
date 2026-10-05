package pro.deta.orion.bootstrap.config.location;

import java.util.Map;

record S3ConfigurationObject(String bucket, String key, Map<String, String> auth) {
    S3ConfigurationObject {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 configuration bucket must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("S3 configuration object key must not be blank");
        }
        auth = Map.copyOf(auth == null ? Map.of() : auth);
    }
}
