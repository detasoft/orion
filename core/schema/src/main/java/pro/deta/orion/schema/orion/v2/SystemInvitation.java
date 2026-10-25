package pro.deta.orion.schema.orion.v2;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** A single-use system invitation bound to one provider and explicit initial repository access. */
public record SystemInvitation(String tokenHash, String email, long expiresAt,
        String provider, List<RepositoryAccess> repositories) {
    public SystemInvitation {
        OrganizationInvitation validated = new OrganizationInvitation(tokenHash, email, expiresAt);
        email = validated.email();
        provider = IdentifierRules.requireCanonical(provider, "invitation provider");
        repositories = List.copyOf(repositories);
        if (repositories.size() > 100) throw new IllegalArgumentException("Too many repository grants");
        Set<String> names = new HashSet<>();
        for (RepositoryAccess access : repositories) {
            if (!names.add(access.repository())) throw new IllegalArgumentException("Duplicate repository grant");
        }
    }

    public record RepositoryAccess(String repository, boolean readWrite) {
        public RepositoryAccess {
            String canonical = RepositoryName.parse(repository).value();
            if (!canonical.equals(repository)) {
                throw new IllegalArgumentException("An exact repository name is required");
            }
        }
    }
}
