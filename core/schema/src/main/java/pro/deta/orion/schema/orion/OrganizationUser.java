package pro.deta.orion.schema.orion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record OrganizationUser(
        UserId id,
        String first,
        String last,
        String email,
        boolean enabled,
        List<UserCredential> credentials,
        List<TeamId> teamMemberships,
        List<RoleAddress> roleAssignments) {
    private static final Comparator<UserCredential> CREDENTIAL_ORDER = Comparator
            .comparing(UserCredential::type)
            .thenComparing(UserCredential::keyId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(UserCredential::value);

    public OrganizationUser {
        Objects.requireNonNull(id, "user id");
        credentials = canonicalize(credentials, CREDENTIAL_ORDER, "credential");
        teamMemberships = canonicalize(
                teamMemberships,
                Comparator.comparing(TeamId::value),
                "team membership");
        roleAssignments = canonicalize(
                roleAssignments,
                Comparator.comparing(RoleAddress::toString),
                "role assignment");
    }

    private static <T> List<T> canonicalize(List<T> source, Comparator<T> comparator, String valueName) {
        Objects.requireNonNull(source, valueName + "s");
        Set<T> unique = new HashSet<>();
        for (T value : source) {
            Objects.requireNonNull(value, valueName);
            if (!unique.add(value)) {
                throw new IllegalArgumentException("duplicate " + valueName + ": " + value);
            }
        }
        List<T> canonical = new ArrayList<>(source);
        canonical.sort(comparator);
        return List.copyOf(canonical);
    }
}
