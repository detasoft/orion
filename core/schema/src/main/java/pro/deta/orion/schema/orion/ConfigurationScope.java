package pro.deta.orion.schema.orion;

import java.util.Objects;
import java.util.Optional;

public record ConfigurationScope(
        OrganizationId organizationId,
        Optional<TeamId> teamId,
        Optional<RepositoryId> repositoryId) {
    public ConfigurationScope {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        if (repositoryId.isPresent() && teamId.isEmpty()) {
            throw new IllegalArgumentException("repository scope requires a team");
        }
    }

    public static ConfigurationScope organization(OrganizationId organizationId) {
        return new ConfigurationScope(organizationId, Optional.empty(), Optional.empty());
    }

    public static ConfigurationScope team(OrganizationId organizationId, TeamId teamId) {
        return new ConfigurationScope(organizationId, Optional.of(teamId), Optional.empty());
    }

    public static ConfigurationScope repository(RepositoryAddress repositoryAddress) {
        Objects.requireNonNull(repositoryAddress, "repositoryAddress");
        return new ConfigurationScope(
                repositoryAddress.organizationId(),
                Optional.of(repositoryAddress.teamId()),
                Optional.of(repositoryAddress.repositoryId()));
    }

    public static ConfigurationScope parse(String value) {
        Objects.requireNonNull(value, "configuration scope");
        String[] segments = value.split("/", -1);
        return switch (segments.length) {
            case 1 -> organization(new OrganizationId(segments[0]));
            case 2 -> team(new OrganizationId(segments[0]), new TeamId(segments[1]));
            case 3 -> repository(RepositoryAddress.parse(value));
            default -> throw new IllegalArgumentException(
                    "configuration scope must have one to three segments: " + value);
        };
    }

    public boolean isSameOrAncestorOf(ConfigurationScope other) {
        Objects.requireNonNull(other, "other");
        if (!organizationId.equals(other.organizationId)) {
            return false;
        }
        if (teamId.isEmpty()) {
            return true;
        }
        if (!teamId.equals(other.teamId)) {
            return false;
        }
        return repositoryId.isEmpty() || repositoryId.equals(other.repositoryId);
    }

    @Override
    public String toString() {
        String value = organizationId.toString();
        if (teamId.isPresent()) {
            value += "/" + teamId.orElseThrow();
        }
        if (repositoryId.isPresent()) {
            value += "/" + repositoryId.orElseThrow();
        }
        return value;
    }
}
