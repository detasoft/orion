package pro.deta.orion.schema.orion;

import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record OrionDocument(SystemConfiguration system, List<Organization> organizations) {
    public OrionDocument {
        Objects.requireNonNull(system, "system");
        organizations = copyOrganizations(organizations);
        OrionDocumentGraphValidator.validate(organizations);
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

    public record Organization(
            OrganizationId id,
            String displayName,
            List<OrganizationUser> users,
            List<ScopedGrant> grants,
            List<ScopedRole> roles,
            List<Team> teams) {
        public Organization {
            Objects.requireNonNull(id, "id");
            users = copyUnique(users, OrganizationUser::id, "user");
            grants = copyUnique(grants, ScopedGrant::id, "grant");
            roles = copyUnique(roles, ScopedRole::id, "role");
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

    public record Team(
            TeamId id,
            String displayName,
            List<ScopedGrant> grants,
            List<ScopedRole> roles,
            List<Repository> repositories) {
        public Team {
            Objects.requireNonNull(id, "id");
            grants = copyUnique(grants, ScopedGrant::id, "grant");
            roles = copyUnique(roles, ScopedRole::id, "role");
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
            List<RepositoryRemote> remotes,
            List<ScopedGrant> grants,
            List<ScopedRole> roles) {
        public static final String DEFAULT_BRANCH = "refs/heads/main";

        public Repository {
            Objects.requireNonNull(id, "id");
            defaultBranch = RemoteRefMapping.requireConcreteBranch(defaultBranch, "default branch");
            Objects.requireNonNull(policy, "repository policy");
            remotes = copyRemotes(remotes);
            grants = copyUnique(grants, ScopedGrant::id, "grant");
            roles = copyUnique(roles, ScopedRole::id, "role");
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

    private static <T, I> List<T> copyUnique(
            List<T> source,
            java.util.function.Function<T, I> idFunction,
            String valueName) {
        Objects.requireNonNull(source, valueName + "s");
        Set<I> ids = new HashSet<>();
        for (T value : source) {
            Objects.requireNonNull(value, valueName);
            I id = idFunction.apply(value);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate " + valueName + " id: " + id);
            }
        }
        return List.copyOf(source);
    }
}

final class OrionDocumentGraphValidator {
    private final Map<RoleAddress, RoleNode> roles = new LinkedHashMap<>();
    private final Map<GrantAddress, ScopedGrant> grants = new LinkedHashMap<>();
    private final Map<RoleAddress, VisitState> roleStates = new LinkedHashMap<>();

    private OrionDocumentGraphValidator() {
    }

    static void validate(List<OrionDocument.Organization> organizations) {
        OrionDocumentGraphValidator validator = new OrionDocumentGraphValidator();
        validator.indexDefinitions(organizations);
        validator.validateUsers(organizations);
        validator.validateRoles();
    }

    private void indexDefinitions(List<OrionDocument.Organization> organizations) {
        for (OrionDocument.Organization organization : organizations) {
            ConfigurationScope organizationScope = ConfigurationScope.organization(organization.id());
            indexDefinitions(organizationScope, organization.grants(), organization.roles());
            for (OrionDocument.Team team : organization.teams()) {
                ConfigurationScope teamScope = ConfigurationScope.team(organization.id(), team.id());
                indexDefinitions(teamScope, team.grants(), team.roles());
                for (OrionDocument.Repository repository : team.repositories()) {
                    RepositoryAddress repositoryAddress = new RepositoryAddress(
                            organization.id(), team.id(), repository.id());
                    ConfigurationScope repositoryScope = ConfigurationScope.repository(repositoryAddress);
                    indexDefinitions(repositoryScope, repository.grants(), repository.roles());
                }
            }
        }
    }

    private void indexDefinitions(
            ConfigurationScope scope,
            List<ScopedGrant> scopedGrants,
            List<ScopedRole> scopedRoles) {
        for (ScopedGrant grant : scopedGrants) {
            grants.put(new GrantAddress(scope, grant.id()), grant);
        }
        for (ScopedRole role : scopedRoles) {
            RoleAddress address = new RoleAddress(scope, role.id());
            roles.put(address, new RoleNode(scope, role));
        }
    }

    private void validateUsers(List<OrionDocument.Organization> organizations) {
        for (OrionDocument.Organization organization : organizations) {
            Set<TeamId> teamIds = new HashSet<>();
            for (OrionDocument.Team team : organization.teams()) {
                teamIds.add(team.id());
            }
            for (OrganizationUser user : organization.users()) {
                validateMemberships(organization.id(), teamIds, user);
                validateAssignments(organization.id(), user);
            }
        }
    }

    private static void validateMemberships(
            OrganizationId organizationId,
            Set<TeamId> teamIds,
            OrganizationUser user) {
        for (TeamId membership : user.teamMemberships()) {
            if (!teamIds.contains(membership)) {
                throw new IllegalArgumentException(
                        "missing team membership: " + organizationId + "/" + membership);
            }
        }
    }

    private void validateAssignments(OrganizationId organizationId, OrganizationUser user) {
        for (RoleAddress assignment : user.roleAssignments()) {
            if (!organizationId.equals(assignment.scope().organizationId())) {
                throw new IllegalArgumentException("role assignment outside organization: " + assignment);
            }
            if (!roles.containsKey(assignment)) {
                throw new IllegalArgumentException("missing assigned role: " + assignment);
            }
        }
    }

    private void validateRoles() {
        for (RoleAddress address : roles.keySet()) {
            if (roleState(address) == VisitState.UNVISITED) {
                validateRoleGraph(address);
            }
        }
    }

    private void validateRoleGraph(RoleAddress root) {
        Deque<RoleTraversalFrame> stack = new ArrayDeque<>();
        pushRole(root, stack);
        while (!stack.isEmpty()) {
            RoleTraversalFrame frame = stack.peek();
            if (frame.hasNextRoleReference()) {
                RoleAddress reference = frame.nextRoleReference();
                validateReferenceScope(frame.node().scope(), reference.scope(), "role", reference);
                if (!roles.containsKey(reference)) {
                    throw new IllegalArgumentException("missing role reference: " + reference);
                }
                VisitState referenceState = roleState(reference);
                if (referenceState == VisitState.VISITING) {
                    throw new IllegalArgumentException("role cycle closes at: " + reference);
                }
                if (referenceState == VisitState.UNVISITED) {
                    pushRole(reference, stack);
                }
                continue;
            }
            validateGrantReferences(frame.node());
            roleStates.put(frame.address(), VisitState.VISITED);
            stack.pop();
        }
    }

    private void pushRole(RoleAddress address, Deque<RoleTraversalFrame> stack) {
        roleStates.put(address, VisitState.VISITING);
        stack.push(new RoleTraversalFrame(address, roles.get(address)));
    }

    private void validateGrantReferences(RoleNode node) {
        for (GrantAddress reference : node.role().grantReferences()) {
            validateReferenceScope(node.scope(), reference.scope(), "grant", reference);
            if (!grants.containsKey(reference)) {
                throw new IllegalArgumentException("missing grant reference: " + reference);
            }
        }
    }

    private VisitState roleState(RoleAddress address) {
        return roleStates.getOrDefault(address, VisitState.UNVISITED);
    }

    private static void validateReferenceScope(
            ConfigurationScope owner,
            ConfigurationScope referenced,
            String referenceType,
            Object reference) {
        if (!referenced.isSameOrAncestorOf(owner)) {
            throw new IllegalArgumentException(referenceType + " reference outside scope: " + reference);
        }
    }

    private record RoleNode(ConfigurationScope scope, ScopedRole role) {
    }

    private static final class RoleTraversalFrame {
        private final RoleAddress address;
        private final RoleNode node;
        private int roleReferenceIndex;

        private RoleTraversalFrame(RoleAddress address, RoleNode node) {
            this.address = address;
            this.node = node;
        }

        private RoleAddress address() {
            return address;
        }

        private RoleNode node() {
            return node;
        }

        private boolean hasNextRoleReference() {
            return roleReferenceIndex < node.role().roleReferences().size();
        }

        private RoleAddress nextRoleReference() {
            return node.role().roleReferences().get(roleReferenceIndex++);
        }
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }
}
