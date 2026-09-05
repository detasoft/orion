package pro.deta.orion.schema.orion;

import java.util.Objects;

public record GrantAddress(ConfigurationScope scope, GrantId grantId) {
    public GrantAddress {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(grantId, "grantId");
    }

    public static GrantAddress parse(String value) {
        Objects.requireNonNull(value, "grant address");
        String[] segments = value.split("/", -1);
        if (segments.length < 2 || segments.length > 4) {
            throw new IllegalArgumentException("grant address must have two to four segments: " + value);
        }
        int separator = value.lastIndexOf('/');
        return new GrantAddress(
                ConfigurationScope.parse(value.substring(0, separator)),
                new GrantId(value.substring(separator + 1)));
    }

    @Override
    public String toString() {
        return scope + "/" + grantId;
    }
}
