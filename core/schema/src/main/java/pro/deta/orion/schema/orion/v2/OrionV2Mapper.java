package pro.deta.orion.schema.orion.v2;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RemoteRefMapping;
import pro.deta.orion.schema.orion.RemoteRole;
import pro.deta.orion.schema.orion.RemoteTrigger;
import pro.deta.orion.schema.orion.RemoteUpdatePolicy;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.RepositoryRemote;
import pro.deta.orion.schema.orion.TeamId;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class OrionV2Mapper {
    private static final Comparator<String> NULL_SAFE_STRINGS =
            Comparator.nullsFirst(Comparator.naturalOrder());

    private OrionV2Mapper() {
    }

    public static OrionDocument toCurrent(OrionV2 source) {
        Objects.requireNonNull(source, "source");
        if (source.getSchemaVersion() != OrionV2.SchemaVersion.V2) {
            throw new IllegalArgumentException("Orion XML v2 schema version is required");
        }
        OrionV2.SystemConfiguration system = Objects.requireNonNull(source.getSystem(), "system");
        AccessControl accessControl = toCurrent(
                Objects.requireNonNull(system.getAccessControl(), "system access control"));
        return new OrionDocument(
                new OrionDocument.SystemConfiguration(accessControl),
                toCurrentOrganizations(source.getOrganizations()));
    }

    public static OrionV2 fromCurrent(OrionDocument source) {
        Objects.requireNonNull(source, "source");
        return new OrionV2(
                OrionV2.SchemaVersion.V2,
                new OrionV2.SystemConfiguration(fromCurrent(source.system().accessControl())),
                fromCurrentOrganizations(source.organizations()));
    }

    private static List<OrionDocument.Organization> toCurrentOrganizations(
            List<OrionV2.Organization> source) {
        List<OrionV2.Organization> sortedOrganizations = sorted(
                source,
                Comparator.comparing(OrionV2.Organization::getId, NULL_SAFE_STRINGS));
        List<OrionDocument.Organization> organizations = new ArrayList<>();
        for (OrionV2.Organization organization : sortedOrganizations) {
            Objects.requireNonNull(organization, "organization");
            organizations.add(new OrionDocument.Organization(
                    new OrganizationId(organization.getId()),
                    organization.getDisplayName(),
                    toCurrentTeams(organization.getTeams())));
        }
        return organizations;
    }

    private static List<OrionDocument.Team> toCurrentTeams(List<OrionV2.Team> source) {
        List<OrionV2.Team> sortedTeams = sorted(
                source,
                Comparator.comparing(OrionV2.Team::getId, NULL_SAFE_STRINGS));
        List<OrionDocument.Team> teams = new ArrayList<>();
        for (OrionV2.Team team : sortedTeams) {
            Objects.requireNonNull(team, "team");
            teams.add(new OrionDocument.Team(
                    new TeamId(team.getId()),
                    team.getDisplayName(),
                    toCurrentRepositories(team.getRepositories())));
        }
        return teams;
    }

    private static List<OrionDocument.Repository> toCurrentRepositories(
            List<OrionV2.Repository> source) {
        List<OrionV2.Repository> sortedRepositories = sorted(
                source,
                Comparator.comparing(OrionV2.Repository::getId, NULL_SAFE_STRINGS));
        List<OrionDocument.Repository> repositories = new ArrayList<>();
        for (OrionV2.Repository repository : sortedRepositories) {
            Objects.requireNonNull(repository, "repository");
            repositories.add(new OrionDocument.Repository(
                    new RepositoryId(repository.getId()),
                    repository.getDisplayName(),
                    repository.getDefaultBranch() == null
                            ? OrionDocument.Repository.DEFAULT_BRANCH
                            : repository.getDefaultBranch(),
                    toCurrent(repository.getPolicy()),
                    toCurrentRemotes(repository.getRemotes())));
        }
        return repositories;
    }

    private static RepositoryPolicy toCurrent(OrionV2.RepositoryPolicy source) {
        if (source == null) {
            return RepositoryPolicy.safeDefaults();
        }
        return new RepositoryPolicy(
                source.isAllowForcePushes(),
                source.isAllowBranchDeletes(),
                source.isAllowTagRewrites());
    }

    private static List<RepositoryRemote> toCurrentRemotes(List<OrionV2.Remote> source) {
        List<OrionV2.Remote> sortedRemotes = sorted(
                source,
                Comparator.comparing(OrionV2.Remote::getAlias, NULL_SAFE_STRINGS));
        List<RepositoryRemote> remotes = new ArrayList<>();
        for (OrionV2.Remote remote : sortedRemotes) {
            Objects.requireNonNull(remote, "repository remote");
            remotes.add(new RepositoryRemote(
                    new RemoteAlias(remote.getAlias()),
                    enumValue(RemoteRole.class, remote.getRole()),
                    enumValue(RemoteProvider.class, remote.getProvider()),
                    URI.create(remote.getUri()),
                    toCurrent(remote.getCredential()),
                    toCurrentTriggers(remote.getTriggers()),
                    toCurrentMappings(remote.getRefMappings()),
                    toCurrent(remote.getUpdatePolicy())));
        }
        return remotes;
    }

    private static ConfigurationSecretReference toCurrent(OrionV2.SecretReference source) {
        Objects.requireNonNull(source, "remote credential");
        return new ConfigurationSecretReference(
                enumValue(ConfigurationSecretReference.Scope.class, source.getScope()),
                source.getReference());
    }

    private static Set<RemoteTrigger> toCurrentTriggers(List<OrionV2.RemoteTrigger> source) {
        EnumSet<RemoteTrigger> triggers = EnumSet.noneOf(RemoteTrigger.class);
        for (OrionV2.RemoteTrigger trigger : listOrEmpty(source)) {
            triggers.add(enumValue(RemoteTrigger.class, trigger));
        }
        return triggers;
    }

    private static List<RemoteRefMapping> toCurrentMappings(List<OrionV2.RefMapping> source) {
        List<OrionV2.RefMapping> sortedMappings = sorted(
                source,
                Comparator.comparing(OrionV2.RefMapping::getSource, NULL_SAFE_STRINGS)
                        .thenComparing(OrionV2.RefMapping::getDestination, NULL_SAFE_STRINGS));
        List<RemoteRefMapping> mappings = new ArrayList<>();
        for (OrionV2.RefMapping mapping : sortedMappings) {
            Objects.requireNonNull(mapping, "remote ref mapping");
            mappings.add(new RemoteRefMapping(mapping.getSource(), mapping.getDestination()));
        }
        return mappings;
    }

    private static RemoteUpdatePolicy toCurrent(OrionV2.RemoteUpdatePolicy source) {
        if (source == null) {
            return RemoteUpdatePolicy.fastForwardOnly();
        }
        return new RemoteUpdatePolicy(
                source.isAllowForceUpdates(),
                source.isAllowDeletes(),
                source.isAllowTagRewrites());
    }

    private static AccessControl toCurrent(OrionV2.AccessControl source) {
        requireUniqueIds(source.getUsers(), OrionV2.User::getId, "ACL user");
        requireUniqueIds(source.getRoles(), OrionV2.Role::getId, "ACL role");
        requireUniqueIds(source.getGrants(), OrionV2.Grant::getId, "ACL grant");

        List<OrionV2.User> sortedUsers = sorted(
                source.getUsers(),
                Comparator.comparing(OrionV2.User::getId, NULL_SAFE_STRINGS));
        List<AccessControl.User> users = new ArrayList<>();
        for (OrionV2.User user : sortedUsers) {
            Objects.requireNonNull(user, "ACL user");
            users.add(toCurrent(user));
        }

        List<OrionV2.Role> sortedRoles = sorted(
                source.getRoles(),
                Comparator.comparing(OrionV2.Role::getId, NULL_SAFE_STRINGS));
        List<AccessControl.Role> roles = new ArrayList<>();
        for (OrionV2.Role role : sortedRoles) {
            Objects.requireNonNull(role, "ACL role");
            roles.add(toCurrent(role));
        }

        return new AccessControl(users, roles, toCurrentGrants(source.getGrants(), "ACL grant"));
    }

    private static AccessControl.User toCurrent(OrionV2.User source) {
        requireUniqueIds(source.getGrants(), OrionV2.Grant::getId, "ACL user grant");
        List<OrionV2.Credential> sortedCredentials = sorted(
                source.getCredentials(),
                Comparator.comparing(
                                (OrionV2.Credential credential) -> enumName(credential.getType()),
                                NULL_SAFE_STRINGS)
                        .thenComparing(OrionV2.Credential::getKeyId, NULL_SAFE_STRINGS)
                        .thenComparing(OrionV2.Credential::getValue, NULL_SAFE_STRINGS));
        List<AccessControl.Credential> credentials = new ArrayList<>();
        for (OrionV2.Credential credential : sortedCredentials) {
            Objects.requireNonNull(credential, "ACL credential");
            credentials.add(new AccessControl.Credential(
                    enumValue(AccessControl.CredentialType.class, credential.getType()),
                    credential.getKeyId(),
                    credential.getValue()));
        }
        return new AccessControl.User(
                source.getId(),
                source.getFirst(),
                source.getLast(),
                source.getEmail(),
                credentials,
                sorted(source.getRoles(), NULL_SAFE_STRINGS),
                toCurrentGrants(source.getGrants(), "ACL user grant"));
    }

    private static AccessControl.Role toCurrent(OrionV2.Role source) {
        requireUniqueIds(source.getGrants(), OrionV2.Grant::getId, "ACL role grant");
        return new AccessControl.Role(
                source.getId(),
                toCurrentGrants(source.getGrants(), "ACL role grant"),
                sorted(source.getGrantReferences(), NULL_SAFE_STRINGS));
    }

    private static List<AccessControl.Grant> toCurrentGrants(
            List<OrionV2.Grant> source,
            String description) {
        requireUniqueIds(source, OrionV2.Grant::getId, description);
        List<OrionV2.Grant> sortedGrants = sorted(
                source,
                Comparator.comparing(OrionV2.Grant::getId, NULL_SAFE_STRINGS));
        List<AccessControl.Grant> grants = new ArrayList<>();
        for (OrionV2.Grant grant : sortedGrants) {
            Objects.requireNonNull(grant, description);
            List<OrionV2.GrantExpression> sortedInfo = sorted(
                    grant.getInfo(),
                    Comparator.comparing(
                                    (OrionV2.GrantExpression expression) -> enumName(expression.getKey()),
                                    NULL_SAFE_STRINGS)
                            .thenComparing(OrionV2.GrantExpression::getValue, NULL_SAFE_STRINGS));
            List<AccessControl.GrantExpression> expressions = new ArrayList<>();
            for (OrionV2.GrantExpression expression : sortedInfo) {
                Objects.requireNonNull(expression, "ACL grant expression");
                expressions.add(new AccessControl.GrantExpression(
                        enumValue(AccessControl.GrantKey.class, expression.getKey()),
                        expression.getValue()));
            }
            grants.add(new AccessControl.Grant(grant.getId(), expressions));
        }
        return grants;
    }

    private static List<OrionV2.Organization> fromCurrentOrganizations(
            List<OrionDocument.Organization> source) {
        List<OrionDocument.Organization> sorted = sorted(
                source,
                Comparator.comparing(organization -> organization.id().value()));
        List<OrionV2.Organization> organizations = new ArrayList<>();
        for (OrionDocument.Organization organization : sorted) {
            organizations.add(new OrionV2.Organization(
                    organization.id().value(),
                    organization.displayName(),
                    fromCurrentTeams(organization.teams())));
        }
        return organizations;
    }

    private static List<OrionV2.Team> fromCurrentTeams(List<OrionDocument.Team> source) {
        List<OrionDocument.Team> sorted = sorted(
                source,
                Comparator.comparing(team -> team.id().value()));
        List<OrionV2.Team> teams = new ArrayList<>();
        for (OrionDocument.Team team : sorted) {
            teams.add(new OrionV2.Team(
                    team.id().value(),
                    team.displayName(),
                    fromCurrentRepositories(team.repositories())));
        }
        return teams;
    }

    private static List<OrionV2.Repository> fromCurrentRepositories(
            List<OrionDocument.Repository> source) {
        List<OrionDocument.Repository> sorted = sorted(
                source,
                Comparator.comparing(repository -> repository.id().value()));
        List<OrionV2.Repository> repositories = new ArrayList<>();
        for (OrionDocument.Repository repository : sorted) {
            repositories.add(new OrionV2.Repository(
                    repository.id().value(),
                    repository.displayName(),
                    repository.defaultBranch(),
                    fromCurrent(repository.policy()),
                    fromCurrentRemotes(repository.remotes())));
        }
        return repositories;
    }

    private static OrionV2.RepositoryPolicy fromCurrent(RepositoryPolicy source) {
        return new OrionV2.RepositoryPolicy(
                source.allowForcePushes(),
                source.allowBranchDeletes(),
                source.allowTagRewrites());
    }

    private static List<OrionV2.Remote> fromCurrentRemotes(List<RepositoryRemote> source) {
        List<RepositoryRemote> sortedRemotes = sorted(
                source,
                Comparator.comparing(remote -> remote.alias().value()));
        List<OrionV2.Remote> remotes = new ArrayList<>();
        for (RepositoryRemote remote : sortedRemotes) {
            remotes.add(new OrionV2.Remote(
                    remote.alias().value(),
                    enumValue(OrionV2.RemoteRole.class, remote.role()),
                    enumValue(OrionV2.RemoteProvider.class, remote.provider()),
                    remote.uri().toString(),
                    fromCurrent(remote.credential()),
                    fromCurrentTriggers(remote.triggers()),
                    fromCurrentMappings(remote.refMappings()),
                    fromCurrent(remote.updatePolicy())));
        }
        return remotes;
    }

    private static OrionV2.SecretReference fromCurrent(ConfigurationSecretReference source) {
        return new OrionV2.SecretReference(
                enumValue(OrionV2.SecretScope.class, source.scope()),
                source.reference());
    }

    private static List<OrionV2.RemoteTrigger> fromCurrentTriggers(Set<RemoteTrigger> source) {
        List<RemoteTrigger> sortedTriggers = sorted(
                new ArrayList<>(source),
                Comparator.comparing(Enum::name));
        List<OrionV2.RemoteTrigger> triggers = new ArrayList<>();
        for (RemoteTrigger trigger : sortedTriggers) {
            triggers.add(enumValue(OrionV2.RemoteTrigger.class, trigger));
        }
        return triggers;
    }

    private static List<OrionV2.RefMapping> fromCurrentMappings(List<RemoteRefMapping> source) {
        List<RemoteRefMapping> sortedMappings = sorted(
                source,
                Comparator.comparing(RemoteRefMapping::source)
                        .thenComparing(RemoteRefMapping::destination));
        List<OrionV2.RefMapping> mappings = new ArrayList<>();
        for (RemoteRefMapping mapping : sortedMappings) {
            mappings.add(new OrionV2.RefMapping(mapping.source(), mapping.destination()));
        }
        return mappings;
    }

    private static OrionV2.RemoteUpdatePolicy fromCurrent(RemoteUpdatePolicy source) {
        return new OrionV2.RemoteUpdatePolicy(
                source.allowForceUpdates(),
                source.allowDeletes(),
                source.allowTagRewrites());
    }

    private static OrionV2.AccessControl fromCurrent(AccessControl source) {
        requireUniqueIds(source.getUsers(), AccessControl.User::getId, "ACL user");
        requireUniqueIds(source.getRoles(), AccessControl.Role::getId, "ACL role");
        requireUniqueIds(source.getGrants(), AccessControl.Grant::getId, "ACL grant");

        List<AccessControl.User> sortedUsers = sorted(
                source.getUsers(),
                Comparator.comparing(AccessControl.User::getId, NULL_SAFE_STRINGS));
        List<OrionV2.User> users = new ArrayList<>();
        for (AccessControl.User user : sortedUsers) {
            users.add(fromCurrent(user));
        }

        List<AccessControl.Role> sortedRoles = sorted(
                source.getRoles(),
                Comparator.comparing(AccessControl.Role::getId, NULL_SAFE_STRINGS));
        List<OrionV2.Role> roles = new ArrayList<>();
        for (AccessControl.Role role : sortedRoles) {
            roles.add(fromCurrent(role));
        }

        return new OrionV2.AccessControl(users, roles, fromCurrentGrants(source.getGrants(), "ACL grant"));
    }

    private static OrionV2.User fromCurrent(AccessControl.User source) {
        List<AccessControl.Credential> sortedCredentials = sorted(
                source.getCredentials(),
                Comparator.comparing(
                                (AccessControl.Credential credential) -> enumName(credential.getType()),
                                NULL_SAFE_STRINGS)
                        .thenComparing(AccessControl.Credential::getKeyId, NULL_SAFE_STRINGS)
                        .thenComparing(AccessControl.Credential::getValue, NULL_SAFE_STRINGS));
        List<OrionV2.Credential> credentials = new ArrayList<>();
        for (AccessControl.Credential credential : sortedCredentials) {
            credentials.add(new OrionV2.Credential(
                    enumValue(OrionV2.CredentialType.class, credential.getType()),
                    credential.getKeyId(),
                    credential.getValue()));
        }

        return new OrionV2.User(
                source.getId(),
                source.getFirst(),
                source.getLast(),
                source.getEmail(),
                credentials,
                sorted(source.getRoles(), NULL_SAFE_STRINGS),
                fromCurrentGrants(source.getGrants(), "ACL user grant"));
    }

    private static OrionV2.Role fromCurrent(AccessControl.Role source) {
        return new OrionV2.Role(
                source.getId(),
                fromCurrentGrants(source.getGrants(), "ACL role grant"),
                sorted(source.getGrantReferences(), NULL_SAFE_STRINGS));
    }

    private static List<OrionV2.Grant> fromCurrentGrants(
            List<AccessControl.Grant> source,
            String description) {
        requireUniqueIds(source, AccessControl.Grant::getId, description);
        List<AccessControl.Grant> sortedGrants = sorted(
                source,
                Comparator.comparing(AccessControl.Grant::getId, NULL_SAFE_STRINGS));
        List<OrionV2.Grant> grants = new ArrayList<>();
        for (AccessControl.Grant grant : sortedGrants) {
            List<AccessControl.GrantExpression> sortedInfo = sorted(
                    grant.getInfo(),
                    Comparator.comparing(
                                    (AccessControl.GrantExpression expression) -> enumName(expression.getKey()),
                                    NULL_SAFE_STRINGS)
                            .thenComparing(AccessControl.GrantExpression::getValue, NULL_SAFE_STRINGS));
            List<OrionV2.GrantExpression> expressions = new ArrayList<>();
            for (AccessControl.GrantExpression expression : sortedInfo) {
                expressions.add(new OrionV2.GrantExpression(
                        enumValue(OrionV2.GrantKey.class, expression.getKey()),
                        expression.getValue()));
            }
            grants.add(new OrionV2.Grant(grant.getId(), expressions));
        }
        return grants;
    }

    private static <T> void requireUniqueIds(
            List<T> source,
            java.util.function.Function<T, String> idExtractor,
            String description) {
        Set<String> ids = new HashSet<>();
        for (T item : listOrEmpty(source)) {
            Objects.requireNonNull(item, description);
            String id = Objects.requireNonNull(idExtractor.apply(item), description + " id");
            String comparisonId = id.toLowerCase(Locale.ROOT);
            if (!ids.add(comparisonId)) {
                throw new IllegalArgumentException("duplicate " + description + " id: " + id);
            }
        }
    }

    private static <T> List<T> sorted(List<T> source, Comparator<? super T> comparator) {
        List<T> result = new ArrayList<>(listOrEmpty(source));
        result.sort(comparator);
        return result;
    }

    private static <T> List<T> listOrEmpty(List<T> source) {
        return source == null ? List.of() : source;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, Enum<?> value) {
        return value == null ? null : Enum.valueOf(type, value.name());
    }
}
