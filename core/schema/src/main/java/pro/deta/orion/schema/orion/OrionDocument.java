package pro.deta.orion.schema.orion;

import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record OrionDocument(SystemConfiguration system, List<Organization> organizations) {
    public OrionDocument {
        Objects.requireNonNull(system, "system");
        organizations = copyOrganizations(organizations);
    }

    public static OrionDocument withAccessControl(AccessControl accessControl) {
        return new OrionDocument(new SystemConfiguration(accessControl), List.of());
    }

    public OrionDocument replaceAccessControl(AccessControl accessControl) {
        return new OrionDocument(
                new SystemConfiguration(accessControl, system.https()),
                organizations);
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

    public record SystemConfiguration(
            AccessControl accessControl,
            Optional<OrionHttpsConfiguration> https) {
        public SystemConfiguration(AccessControl accessControl) {
            this(accessControl, Optional.empty());
        }

        public SystemConfiguration {
            Objects.requireNonNull(accessControl, "accessControl");
            https = Objects.requireNonNullElseGet(https, Optional::empty);
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

    public record Repository(
            RepositoryId id,
            String displayName,
            String defaultBranch,
            RepositoryPolicy policy,
            List<RepositoryRemote> remotes) {
        public static final String DEFAULT_BRANCH = "refs/heads/main";

        public Repository {
            Objects.requireNonNull(id, "id");
            defaultBranch = RemoteRefMapping.requireConcreteBranch(defaultBranch, "default branch");
            Objects.requireNonNull(policy, "repository policy");
            remotes = copyRemotes(remotes);
        }

        private static List<RepositoryRemote> copyRemotes(List<RepositoryRemote> source) {
            Objects.requireNonNull(source, "repository remotes");
            List<RepositoryRemote> remotes = new ArrayList<>(source);
            Set<RemoteAlias> aliases = new HashSet<>();
            for (RepositoryRemote remote : remotes) {
                Objects.requireNonNull(remote, "repository remote");
                if (!aliases.add(remote.alias())) {
                    throw new IllegalArgumentException("duplicate remote alias: " + remote.alias());
                }
            }
            remotes.sort(Comparator.comparing(remote -> remote.alias().value()));
            return List.copyOf(remotes);
        }
    }
}
