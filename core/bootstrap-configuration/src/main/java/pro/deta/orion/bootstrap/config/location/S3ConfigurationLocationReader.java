package pro.deta.orion.bootstrap.config.location;

import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.util.Optional;

final class S3ConfigurationLocationReader implements ConfigurationLocationReader {
    private final S3ObjectClient client;

    S3ConfigurationLocationReader(S3ObjectClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(ResourceLocation location) {
        return location.scheme() instanceof ResourceScheme.Other other && "s3".equals(other.value());
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        String bucket = location.host();
        String key = stripLeadingSlash(location.path());
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 configuration location must include bucket name");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("S3 configuration location must include object key");
        }
        return client.readObject(new S3ConfigurationObject(
                        bucket,
                        key,
                        ConfigurationLocationParameters.query(location)))
                .map(content -> new ConfigurationContent(key, content));
    }

    private static String stripLeadingSlash(String value) {
        if (value == null) {
            return "";
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
