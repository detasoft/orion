package pro.deta.orion.schema.orion;

import pro.deta.orion.schema.acl.AccessControl;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record OrionDocument(SystemConfiguration system, List<Organization> organizations) {
    public OrionDocument {
        Objects.requireNonNull(system, "system");
        organizations = copyOrganizations(organizations);
    }

    public static OrionDocument withAccessControl(AccessControl accessControl) {
        return new OrionDocument(new SystemConfiguration(accessControl), List.of());
    }

    private static List<Organization> copyOrganizations(List<Organization> source) {
        Objects.requireNonNull(source, "organizations");
        Set<OrganizationId> ids = new HashSet<>();
        for (Organization organization : source) {
            Objects.requireNonNull(organization, "organization");
            if (!ids.add(organization.id())) {
                throw new IllegalArgumentException("duplicate organization id: " + organization.id());
            }
        }
        return List.copyOf(source);
    }

    public record SystemConfiguration(AccessControl accessControl) {
        public SystemConfiguration {
            Objects.requireNonNull(accessControl, "accessControl");
        }
    }

    public record Organization(OrganizationId id, String displayName, List<Team> teams) {
        public Organization {
            Objects.requireNonNull(id, "id");
            teams = copyTeams(teams);
        }

        private static List<Team> copyTeams(List<Team> source) {
            Objects.requireNonNull(source, "teams");
            Set<TeamId> ids = new HashSet<>();
            for (Team team : source) {
                Objects.requireNonNull(team, "team");
                if (!ids.add(team.id())) {
                    throw new IllegalArgumentException("duplicate team id: " + team.id());
                }
            }
            return List.copyOf(source);
        }
    }

    public record Team(TeamId id, String displayName, List<Repository> repositories) {
        public Team {
            Objects.requireNonNull(id, "id");
            repositories = copyRepositories(repositories);
        }

        private static List<Repository> copyRepositories(List<Repository> source) {
            Objects.requireNonNull(source, "repositories");
            Set<RepositoryId> ids = new HashSet<>();
            for (Repository repository : source) {
                Objects.requireNonNull(repository, "repository");
                if (!ids.add(repository.id())) {
                    throw new IllegalArgumentException("duplicate repository id: " + repository.id());
                }
            }
            return List.copyOf(source);
        }
    }

    public record Repository(RepositoryId id, String displayName) {
        public Repository {
            Objects.requireNonNull(id, "id");
        }
    }
}
