package pro.deta.orion.schema.orion;

import java.util.Objects;

public record RepositoryAddress(
        OrganizationId organizationId,
        TeamId teamId,
        RepositoryId repositoryId) {
    public RepositoryAddress {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(repositoryId, "repositoryId");
    }

    public static RepositoryAddress parse(String value) {
        Objects.requireNonNull(value, "repository address");
        String[] segments = value.split("/", -1);
        if (segments.length != 3) {
            throw new IllegalArgumentException("repository address must have three segments: " + value);
        }
        return new RepositoryAddress(
                new OrganizationId(segments[0]),
                new TeamId(segments[1]),
                new RepositoryId(segments[2]));
    }

    @Override
    public String toString() {
        return organizationId + "/" + teamId + "/" + repositoryId;
    }
}
