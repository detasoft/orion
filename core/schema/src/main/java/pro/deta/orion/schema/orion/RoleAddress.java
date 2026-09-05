package pro.deta.orion.schema.orion;

import java.util.Objects;

public record RoleAddress(ConfigurationScope scope, RoleId roleId) {
    public RoleAddress {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(roleId, "roleId");
    }

    public static RoleAddress parse(String value) {
        Objects.requireNonNull(value, "role address");
        String[] segments = value.split("/", -1);
        if (segments.length < 2 || segments.length > 4) {
            throw new IllegalArgumentException("role address must have two to four segments: " + value);
        }
        int separator = value.lastIndexOf('/');
        return new RoleAddress(
                ConfigurationScope.parse(value.substring(0, separator)),
                new RoleId(value.substring(separator + 1)));
    }

    @Override
    public String toString() {
        return scope + "/" + roleId;
    }
}
