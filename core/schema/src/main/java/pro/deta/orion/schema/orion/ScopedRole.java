package pro.deta.orion.schema.orion;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ScopedRole(
        RoleId id,
        List<RoleAddress> roleReferences,
        List<GrantAddress> grantReferences) {
    public ScopedRole {
        Objects.requireNonNull(id, "role id");
        roleReferences = copyUnique(roleReferences, "role reference");
        grantReferences = copyUnique(grantReferences, "grant reference");
    }

    private static <T> List<T> copyUnique(List<T> source, String valueName) {
        Objects.requireNonNull(source, valueName + "s");
        Set<T> unique = new HashSet<>();
        for (T value : source) {
            Objects.requireNonNull(value, valueName);
            if (!unique.add(value)) {
                throw new IllegalArgumentException("duplicate " + valueName + ": " + value);
            }
        }
        return List.copyOf(source);
    }
}
