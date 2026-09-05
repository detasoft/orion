package pro.deta.orion.schema.orion.v2;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.GrantId;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.OrganizationUser;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RemoteRefMapping;
import pro.deta.orion.schema.orion.RemoteRole;
import pro.deta.orion.schema.orion.RemoteTrigger;
import pro.deta.orion.schema.orion.RemoteUpdatePolicy;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.RepositoryRemote;
import pro.deta.orion.schema.orion.RoleAddress;
import pro.deta.orion.schema.orion.RoleId;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.ScopedRole;
import pro.deta.orion.schema.orion.TeamId;
import pro.deta.orion.schema.orion.UserCredential;
import pro.deta.orion.schema.orion.UserId;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
                new OrionDocument.SystemConfiguration(
                        accessControl,
                        Optional.ofNullable(system.getHttps()).map(OrionV2Mapper::toCurrent)),
                toCurrentOrganizations(source.getOrganizations()));
    }

    public static OrionV2 fromCurrent(OrionDocument source) {
        Objects.requireNonNull(source, "source");
        return new OrionV2(
                OrionV2.SchemaVersion.V2,
                new OrionV2.SystemConfiguration(
                        fromCurrent(source.system().accessControl()),
                        source.system().https().map(OrionV2Mapper::fromCurrent).orElse(null)),
                fromCurrentOrganizations(source.organizations()));
    }

    private static OrionHttpsConfiguration toCurrent(OrionV2.Https source) {
        List<OrionMaterialReference> clientRoots = new ArrayList<>();
        for (OrionV2.MaterialReference reference : listOrEmpty(source.getClientTrustAnchors())) {
            clientRoots.add(toCurrent(reference));
        }
        return new OrionHttpsConfiguration(
                source.isEnabled(),
                source.getAddress(),
                source.getPort(),
                source.getPublicUrl() == null ? null : URI.create(source.getPublicUrl()),
                Optional.ofNullable(source.getIdentity()).map(OrionV2Mapper::toCurrent),
                Optional.ofNullable(source.getServerIssuerTrustAnchor()).map(OrionV2Mapper::toCurrent),
                toCurrent(source.getClientAuthentication()),
                clientRoots,
                Optional.ofNullable(source.getAcme()).map(OrionV2Mapper::toCurrent));
    }

    private static OrionV2.Https fromCurrent(OrionHttpsConfiguration source) {
        List<OrionV2.MaterialReference> clientRoots = new ArrayList<>();
        for (OrionMaterialReference reference : source.clientTrustAnchors()) {
            clientRoots.add(fromCurrent(reference));
        }
        return new OrionV2.Https(
                source.enabled(),
                source.address(),
                source.port(),
                source.publicUrl() == null ? null : source.publicUrl().toString(),
                source.identity().map(OrionV2Mapper::fromCurrent).orElse(null),
                source.serverIssuerTrustAnchor().map(OrionV2Mapper::fromCurrent).orElse(null),
                fromCurrent(source.clientAuthentication()),
                clientRoots,
                source.acme().map(OrionV2Mapper::fromCurrent).orElse(null));
    }

    private static OrionAcmeConfiguration toCurrent(OrionV2.Acme source) {
        return new OrionAcmeConfiguration(
                source.isEnabled(),
                URI.create(source.getDirectoryUrl()),
                source.getAccountEmail(),
                source.getDomains(),
                source.getOrganization(),
                Optional.ofNullable(source.getAccountMaterial()).map(OrionV2Mapper::toCurrent),
                source.getAuthorizationTimeoutSeconds(),
                source.getOrderTimeoutSeconds(),
                source.isAgreeToTermsOfService(),
                source.isAllowRequestedDomains());
    }

    private static OrionV2.Acme fromCurrent(OrionAcmeConfiguration source) {
        return new OrionV2.Acme(
                source.enabled(),
                source.directoryUrl().toString(),
                source.accountEmail(),
                source.domains(),
                source.organization(),
                source.accountMaterial().map(OrionV2Mapper::fromCurrent).orElse(null),
                source.authorizationTimeoutSeconds(),
                source.orderTimeoutSeconds(),
                source.agreeToTermsOfService(),
                source.allowRequestedDomains());
    }

    private static OrionMaterialReference toCurrent(OrionV2.MaterialReference source) {
        return new OrionMaterialReference(source.getAlias(), source.getVersion());
    }

    private static OrionV2.MaterialReference fromCurrent(OrionMaterialReference source) {
        return new OrionV2.MaterialReference(source.alias(), source.version());
    }

    private static OrionHttpsConfiguration.ClientAuthentication toCurrent(
            OrionV2.ClientAuthentication source) {
        if (source == null) {
            return OrionHttpsConfiguration.ClientAuthentication.DISABLED;
        }
        return enumValue(OrionHttpsConfiguration.ClientAuthentication.class, source);
    }

    private static OrionV2.ClientAuthentication fromCurrent(
            OrionHttpsConfiguration.ClientAuthentication source) {
        return enumValue(OrionV2.ClientAuthentication.class, source);
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
                    toCurrentOrganizationUsers(organization.getUsers()),
                    toCurrentScopedGrants(organization.getGrants()),
                    toCurrentScopedRoles(organization.getRoles()),
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
                    toCurrentScopedGrants(team.getGrants()),
                    toCurrentScopedRoles(team.getRoles()),
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
                    toCurrentRemotes(repository.getRemotes()),
                    toCurrentScopedGrants(repository.getGrants()),
                    toCurrentScopedRoles(repository.getRoles())));
        }
        return repositories;
    }

    private static List<OrganizationUser> toCurrentOrganizationUsers(
            List<OrionV2.OrganizationUser> source) {
        requireUniqueIds(source, OrionV2.OrganizationUser::getId, "user");
        List<OrionV2.OrganizationUser> sortedUsers = sorted(
                source,
                Comparator.comparing(OrionV2.OrganizationUser::getId, NULL_SAFE_STRINGS));
        List<OrganizationUser> users = new ArrayList<>();
        for (OrionV2.OrganizationUser user : sortedUsers) {
            Objects.requireNonNull(user, "organization user");
            users.add(new OrganizationUser(
                    new UserId(user.getId()),
                    user.getFirst(),
                    user.getLast(),
                    user.getEmail(),
                    user.isEnabled(),
                    toCurrentOrganizationCredentials(user.getCredentials()),
                    toCurrentTeamIds(user.getMemberships()),
                    toCurrentRoleAddresses(user.getRoles())));
        }
        return users;
    }

    private static List<UserCredential> toCurrentOrganizationCredentials(
            List<OrionV2.OrganizationCredential> source) {
        List<OrionV2.OrganizationCredential> sortedCredentials = sorted(
                source,
                Comparator.comparing(
                                (OrionV2.OrganizationCredential credential) -> enumName(credential.getType()),
                                NULL_SAFE_STRINGS)
                        .thenComparing(OrionV2.OrganizationCredential::getKeyId, NULL_SAFE_STRINGS)
                        .thenComparing(OrionV2.OrganizationCredential::getValue, NULL_SAFE_STRINGS));
        List<UserCredential> credentials = new ArrayList<>();
        for (OrionV2.OrganizationCredential credential : sortedCredentials) {
            Objects.requireNonNull(credential, "organization credential");
            UserCredential.Type type = enumValue(UserCredential.Type.class, credential.getType());
            credentials.add(switch (Objects.requireNonNull(type, "credential type")) {
                case ARGON2, SHA1 -> toCurrentPasswordCredential(credential, type);
                case OPENSSH_PUBLIC_KEY -> UserCredential.publicKey(
                        credential.getKeyId(), credential.getValue());
            });
        }
        return credentials;
    }

    private static UserCredential toCurrentPasswordCredential(
            OrionV2.OrganizationCredential source,
            UserCredential.Type type) {
        if (source.getKeyId() != null) {
            throw new IllegalArgumentException("password credential key id must be absent");
        }
        return UserCredential.passwordVerifier(type, source.getValue());
    }

    private static List<TeamId> toCurrentTeamIds(List<String> source) {
        List<TeamId> ids = new ArrayList<>();
        for (String value : sorted(source, NULL_SAFE_STRINGS)) {
            ids.add(new TeamId(value));
        }
        return ids;
    }

    private static List<RoleAddress> toCurrentRoleAddresses(List<String> source) {
        List<RoleAddress> addresses = new ArrayList<>();
        for (String value : sorted(source, NULL_SAFE_STRINGS)) {
            addresses.add(RoleAddress.parse(value));
        }
        return addresses;
    }

    private static List<GrantAddress> toCurrentGrantAddresses(List<String> source) {
        List<GrantAddress> addresses = new ArrayList<>();
        for (String value : sorted(source, NULL_SAFE_STRINGS)) {
            addresses.add(GrantAddress.parse(value));
        }
        return addresses;
    }

    private static List<ScopedRole> toCurrentScopedRoles(List<OrionV2.ScopedRole> source) {
        requireUniqueIds(source, OrionV2.ScopedRole::getId, "role");
        List<OrionV2.ScopedRole> sortedRoles = sorted(
                source,
                Comparator.comparing(OrionV2.ScopedRole::getId, NULL_SAFE_STRINGS));
        List<ScopedRole> roles = new ArrayList<>();
        for (OrionV2.ScopedRole role : sortedRoles) {
            Objects.requireNonNull(role, "scoped role");
            roles.add(new ScopedRole(
                    new RoleId(role.getId()),
                    toCurrentRoleAddresses(role.getRoleReferences()),
                    toCurrentGrantAddresses(role.getGrantReferences())));
        }
        return roles;
    }

    private static List<ScopedGrant> toCurrentScopedGrants(List<OrionV2.ScopedGrant> source) {
        requireUniqueIds(source, OrionV2.ScopedGrant::getId, "grant");
        List<OrionV2.ScopedGrant> sortedGrants = sorted(
                source,
                Comparator.comparing(OrionV2.ScopedGrant::getId, NULL_SAFE_STRINGS));
        List<ScopedGrant> grants = new ArrayList<>();
        for (OrionV2.ScopedGrant grant : sortedGrants) {
            Objects.requireNonNull(grant, "scoped grant");
            grants.add(new ScopedGrant(
                    new GrantId(grant.getId()),
                    enumValue(ScopedGrant.Effect.class, grant.getEffect()),
                    toCurrentScopedExpressions(grant.getExpressions())));
        }
        return grants;
    }

    private static List<AccessControl.GrantExpression> toCurrentScopedExpressions(
            List<OrionV2.ScopedGrantExpression> source) {
        List<OrionV2.ScopedGrantExpression> sortedExpressions = sorted(
                source,
                Comparator.comparing(
                                (OrionV2.ScopedGrantExpression expression) -> enumName(expression.getKey()),
                                NULL_SAFE_STRINGS)
                        .thenComparing(OrionV2.ScopedGrantExpression::getValue, NULL_SAFE_STRINGS));
        List<AccessControl.GrantExpression> expressions = new ArrayList<>();
        for (OrionV2.ScopedGrantExpression expression : sortedExpressions) {
            Objects.requireNonNull(expression, "scoped grant expression");
            expressions.add(new AccessControl.GrantExpression(
                    enumValue(AccessControl.GrantKey.class, expression.getKey()),
                    expression.getValue()));
        }
        return expressions;
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
                    fromCurrentOrganizationUsers(organization.users()),
                    fromCurrentScopedGrants(organization.grants()),
                    fromCurrentScopedRoles(organization.roles()),
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
                    fromCurrentScopedGrants(team.grants()),
                    fromCurrentScopedRoles(team.roles()),
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
                    fromCurrentRemotes(repository.remotes()),
                    fromCurrentScopedGrants(repository.grants()),
                    fromCurrentScopedRoles(repository.roles())));
        }
        return repositories;
    }

    private static List<OrionV2.OrganizationUser> fromCurrentOrganizationUsers(
            List<OrganizationUser> source) {
        List<OrganizationUser> sortedUsers = sorted(
                source,
                Comparator.comparing(user -> user.id().value()));
        List<OrionV2.OrganizationUser> users = new ArrayList<>();
        for (OrganizationUser user : sortedUsers) {
            users.add(new OrionV2.OrganizationUser(
                    user.id().value(),
                    user.enabled(),
                    user.first(),
                    user.last(),
                    user.email(),
                    fromCurrentOrganizationCredentials(user.credentials()),
                    fromCurrentTeamIds(user.teamMemberships()),
                    fromCurrentRoleAddresses(user.roleAssignments())));
        }
        return users;
    }

    private static List<OrionV2.OrganizationCredential> fromCurrentOrganizationCredentials(
            List<UserCredential> source) {
        List<UserCredential> sortedCredentials = sorted(
                source,
                Comparator.comparing(UserCredential::type)
                        .thenComparing(UserCredential::keyId, NULL_SAFE_STRINGS)
                        .thenComparing(UserCredential::value));
        List<OrionV2.OrganizationCredential> credentials = new ArrayList<>();
        for (UserCredential credential : sortedCredentials) {
            credentials.add(new OrionV2.OrganizationCredential(
                    enumValue(OrionV2.OrganizationCredentialType.class, credential.type()),
                    credential.keyId(),
                    credential.value()));
        }
        return credentials;
    }

    private static List<String> fromCurrentTeamIds(List<TeamId> source) {
        List<TeamId> sortedIds = sorted(source, Comparator.comparing(TeamId::value));
        List<String> ids = new ArrayList<>();
        for (TeamId id : sortedIds) {
            ids.add(id.value());
        }
        return ids;
    }

    private static List<String> fromCurrentRoleAddresses(List<RoleAddress> source) {
        List<RoleAddress> sortedAddresses = sorted(source, Comparator.comparing(RoleAddress::toString));
        List<String> addresses = new ArrayList<>();
        for (RoleAddress address : sortedAddresses) {
            addresses.add(address.toString());
        }
        return addresses;
    }

    private static List<String> fromCurrentGrantAddresses(List<GrantAddress> source) {
        List<GrantAddress> sortedAddresses = sorted(source, Comparator.comparing(GrantAddress::toString));
        List<String> addresses = new ArrayList<>();
        for (GrantAddress address : sortedAddresses) {
            addresses.add(address.toString());
        }
        return addresses;
    }

    private static List<OrionV2.ScopedRole> fromCurrentScopedRoles(List<ScopedRole> source) {
        List<ScopedRole> sortedRoles = sorted(
                source,
                Comparator.comparing(role -> role.id().value()));
        List<OrionV2.ScopedRole> roles = new ArrayList<>();
        for (ScopedRole role : sortedRoles) {
            roles.add(new OrionV2.ScopedRole(
                    role.id().value(),
                    fromCurrentRoleAddresses(role.roleReferences()),
                    fromCurrentGrantAddresses(role.grantReferences())));
        }
        return roles;
    }

    private static List<OrionV2.ScopedGrant> fromCurrentScopedGrants(List<ScopedGrant> source) {
        List<ScopedGrant> sortedGrants = sorted(
                source,
                Comparator.comparing(grant -> grant.id().value()));
        List<OrionV2.ScopedGrant> grants = new ArrayList<>();
        for (ScopedGrant grant : sortedGrants) {
            grants.add(new OrionV2.ScopedGrant(
                    grant.id().value(),
                    enumValue(OrionV2.ScopedGrantEffect.class, grant.effect()),
                    fromCurrentScopedExpressions(grant.expressions())));
        }
        return grants;
    }

    private static List<OrionV2.ScopedGrantExpression> fromCurrentScopedExpressions(
            List<AccessControl.GrantExpression> source) {
        List<AccessControl.GrantExpression> sortedExpressions = sorted(
                source,
                Comparator.comparing(
                                (AccessControl.GrantExpression expression) -> enumName(expression.getKey()),
                                NULL_SAFE_STRINGS)
                        .thenComparing(AccessControl.GrantExpression::getValue, NULL_SAFE_STRINGS));
        List<OrionV2.ScopedGrantExpression> expressions = new ArrayList<>();
        for (AccessControl.GrantExpression expression : sortedExpressions) {
            expressions.add(new OrionV2.ScopedGrantExpression(
                    enumValue(OrionV2.GrantKey.class, expression.getKey()),
                    expression.getValue()));
        }
        return expressions;
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
