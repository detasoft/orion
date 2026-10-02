package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import pro.deta.orion.config.OrionConfigurationEditor;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.StorageManagement;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.MatcherUtils;
import pro.deta.orion.auth.check.ScopedAccess;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.v2.*;
import pro.deta.orion.util.Result;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Owns authorized configuration changes before delegating metadata creation to the configured provider. */
@Singleton
public final class ConfiguredStorageManagement implements StorageManagement {
    private final OrionAccessControlServiceImpl acl;
    private final OrionConfigurationEditor editor;
    private final OrionDesiredState desired;
    private final ConfigurationSecrets secrets;
    private final NativeGitRepositoryProvider repositories;

    @Inject
    public ConfiguredStorageManagement(OrionAccessControlServiceImpl acl, OrionConfigurationEditor editor,
            OrionDesiredState desired,
            ConfigurationCipherCapability cipher, NativeGitRepositoryProvider repositories) {
        this.acl = acl;
        this.editor = editor;
        this.desired = desired;
        this.secrets = new ConfigurationSecrets(() -> desired.current().document(), cipher);
        this.repositories = repositories;
    }

    @Override
    public Outcome<Created> createRepository(SecurityContext actor, String requested, Optional<S3StorageBinding> storage) {
        return safely(() -> {
            String name = RepositoryName.parse(requested).value();
            OrionDesiredState.Snapshot snapshot = desired.current();
            requireRepositoryCreate(actor, snapshot.document(), name);
            if (storage.isPresent()) {
                RepositoryAddress address = RepositoryAddress.parse(name);
                if (name.startsWith("bootstrap/") || name.startsWith("proxy/")) throw new IllegalArgumentException();
                S3StorageBinding binding = storage.orElseThrow();
                pro.deta.orion.git.s3.S3NativeGitRepositoryProvider.validateLocation(binding.location().toString());
                editor.edit(snapshot.revision().orElseThrow()).update(document -> {
                    requireRepositoryCreate(actor, document, name);
                    Optional<OrganizationId> owner = binding.connection().scope() == ConnectionReference.Scope.SYSTEM
                            ? Optional.empty() : Optional.of(address.organizationId());
                    require(connectionAllowed(actor, document, owner, binding.connection().name(),
                            AccessControl.GrantKey.CONNECTION_USE));
                    Connection connection = OrionDocument.findConnection(connections(document, owner),
                            binding.connection().name());
                    if (!(connection instanceof Connection.S3 s3)) throw new IllegalArgumentException();
                    // Organization-local grants never authorize the server's default credential chain.
                    if (s3.secretKey().isEmpty()) require(admin(actor, document));
                    return bind(document, address, binding);
                }).apply("Create S3 repository", new UserEmail(actor.getUserIdentity().getUserId(), ""));
            } else if (binding(snapshot.document(), name).isPresent()) {
                throw new Conflict();
            }
            Result<NativeGitRepository> result;
            try {
                result = repositories.create(name);
            } catch (RuntimeException unavailable) {
                result = new Result.Failure<>(Result.FailureCode.GENERAL);
            }
            if (result instanceof Result.Success<NativeGitRepository>) {
                return new Success<>(new Created(true));
            }
            Result.Failure<NativeGitRepository> failure = (Result.Failure<NativeGitRepository>) result;
            if (failure.code() == Result.FailureCode.FILE_ALREADY_EXISTS) return new Success<>(new Created(false));
            return storage.isPresent()
                    ? new Failure<>(FailureCode.STORAGE_RETRY,
                            "Storage metadata creation failed. The binding is saved; retry the identical request.")
                    : new Failure<>(FailureCode.UNAVAILABLE, "Repository creation failed");
        });
    }

    @Override
    public Outcome<Connections> connections(SecurityContext actor, Optional<OrganizationId> owner) {
        return safely(() -> {
            OrionDesiredState.Snapshot snapshot = desired.current();
            return new Success<>(view(actor, owner, snapshot));
        });
    }

    @Override
    public Outcome<Connections> saveConnection(SecurityContext actor, Optional<OrganizationId> owner,
            String revision, boolean create, S3Input input) {
        try {
            return safely(() -> {
                if (input == null) throw new IllegalArgumentException();
                // Check scope before revision validation to avoid exposing another organization's configuration.
                requireOwner(actor, desired.current().document(), owner);
                OrionDesiredState.Snapshot saved = editor.edit(revision).update(document -> {
                    require(connectionAllowed(actor, document, owner, input.name(), create
                            ? AccessControl.GrantKey.CREATE : AccessControl.GrantKey.READ_WRITE));
                    Connection existing = null;
                    for (Connection connection : connections(document, owner)) {
                        if (connection.name().equals(input.name())) existing = connection;
                    }
                    if (create == (existing != null)) throw new Conflict();
                    if (existing != null && !(existing instanceof Connection.S3)) throw new Conflict();
                    Connection.S3 previous = (Connection.S3) existing;
                    Optional<String> keyId = Optional.ofNullable(input.accessKeyId());
                    Optional<String> secret = previous == null ? Optional.empty() : previous.secretKey();
                    Optional<String> token = previous == null ? Optional.empty() : previous.sessionToken();
                    if (keyId.isEmpty() && previous != null) keyId = previous.accessKeyId();
                    OrionDocument updated = document;
                    if (input.defaultCredentials()) {
                        require(admin(actor, document));
                        if (input.accessKeyId() != null || input.secretKey() != null || input.sessionToken() != null) {
                            throw new IllegalArgumentException();
                        }
                        keyId = Optional.empty();
                        secret = Optional.empty();
                        token = Optional.empty();
                    } else {
                        if (input.secretKey() != null) {
                            String id = "s3-key-" + UUID.randomUUID();
                            updated = encrypt(updated, owner, id, input.secretKey());
                            secret = Optional.of(id);
                        }
                        if (input.sessionToken() != null) {
                            token = Optional.empty();
                            if (input.sessionToken().length > 0) {
                                String id = "s3-token-" + UUID.randomUUID();
                                updated = encrypt(updated, owner, id, input.sessionToken());
                                token = Optional.of(id);
                            }
                        }
                        if (secret.isEmpty()) throw new IllegalArgumentException();
                    }
                    Connection.S3 replacement = new Connection.S3(input.name(),
                            Optional.ofNullable(input.endpoint()).filter(value -> !value.isBlank()).map(URI::create),
                            input.region(), input.pathStyleAccess(), keyId, secret, token);
                    List<Connection> updatedConnections = new ArrayList<>(connections(updated, owner));
                    updatedConnections.removeIf(connection -> connection.name().equals(input.name()));
                    updatedConnections.add(replacement);
                    return withConnections(updated, owner, updatedConnections);
                }).apply(create ? "Create S3 connection" : "Update S3 connection", new UserEmail(actor.getUserIdentity().getUserId(),
                        ""));
                return new Success<>(view(actor, owner, saved));
            });
        } finally {
            if (input != null) {
                if (input.secretKey() != null) Arrays.fill(input.secretKey(), '\0');
                if (input.sessionToken() != null) Arrays.fill(input.sessionToken(), '\0');
            }
        }
    }

    private Connections view(SecurityContext actor, Optional<OrganizationId> owner,
            OrionDesiredState.Snapshot snapshot) {
        OrionDocument document = snapshot.document();
        requireOwner(actor, document, owner);
        List<S3View> views = new ArrayList<>();
        for (Connection connection : connections(document, owner)) {
            if (!(connection instanceof Connection.S3 s3)) continue;
            boolean use = connectionAllowed(actor, document, owner, s3.name(), AccessControl.GrantKey.CONNECTION_USE)
                    && (s3.secretKey().isPresent() || admin(actor, document));
            boolean change = connectionAllowed(actor, document, owner, s3.name(), AccessControl.GrantKey.READ_WRITE);
            if (!use && !change && !connectionAllowed(actor, document, owner, s3.name(), AccessControl.GrantKey.READ)) {
                continue;
            }
            views.add(new S3View(s3.name(), s3.endpoint().map(URI::toString).orElse(""), s3.region(),
                    s3.pathStyleAccess(), s3.accessKeyId().orElse(""), s3.secretKey().isPresent(),
                    s3.sessionToken().isPresent(), change, use));
        }
        return new Connections(snapshot.revision().orElseThrow(), List.copyOf(views));
    }

    private boolean connectionAllowed(SecurityContext actor, OrionDocument document, Optional<OrganizationId> owner,
                                      String name, AccessControl.GrantKey action) {
        if (admin(actor, document)) return true;
        UserIdentity identity = actor.getUserIdentity();
        if (identity.isAnonymous() || owner.isEmpty() || !identity.getOrganizationId().equals(owner)) return false;
        return ScopedAccess.allows(organization(document, owner.orElseThrow()), new UserId(identity.getUserId()),
                ConfigurationScope.organization(owner.orElseThrow()), expressions -> {
                    for (AccessControl.GrantExpression expression : expressions) {
                        switch (expression.getKey()) {
                            case REPOSITORY, BRANCH, NETWORK_SOURCE, NETWORK_PORT, ADMIN, SHUTDOWN -> { return false; }
                            default -> { }
                        }
                    }
                    return GrantMatcher.of(AccessControl.GrantKey.CONNECTION,
                            pattern -> MatcherUtils.matchExpressionValue(pattern, name)).matchesAny(expressions)
                            && GrantMatcher.of(action).matchesAny(expressions);
                });
    }

    private void requireOwner(SecurityContext actor, OrionDocument document, Optional<OrganizationId> owner) {
        require(admin(actor, document) || !actor.getUserIdentity().isAnonymous()
                && owner.isPresent() && actor.getUserIdentity().getOrganizationId().equals(owner));
    }

    private void requireRepositoryCreate(SecurityContext actor, OrionDocument document, String name) {
        if (admin(actor, document)) return;
        UserIdentity identity = actor.getUserIdentity();
        require(!identity.isAnonymous() && identity.getOrganizationId().isPresent());
        SecurityContext current = SecurityContext.createContext().withUserIdentity(new InternalUserImpl(
                identity.getUserId(), identity.getOrganizationId().orElseThrow(), () -> document));
        require(RepositoryAccessRules.create().evaluate(current, RepositoryResource.of(name)).allowed());
    }

    private boolean admin(SecurityContext actor, OrionDocument document) {
        UserIdentity identity = actor.getUserIdentity();
        return !identity.isAnonymous() && identity.getOrganizationId().isEmpty()
                && acl.canAdminister(new PrincipalAddress.SystemPrincipalAddress(new UserId(identity.getUserId())),
                        Optional.empty(), document);
    }

    private OrionDocument bind(OrionDocument document, RepositoryAddress address, S3StorageBinding storage) {
        OrionDocument.Organization organization = organization(document, address.organizationId());
        List<OrionDocument.Team> teams = new ArrayList<>();
        boolean found = false;
        for (OrionDocument.Team team : organization.teams()) {
            if (!team.id().equals(address.teamId())) { teams.add(team); continue; }
            found = true;
            List<OrionDocument.Repository> entries = new ArrayList<>(team.repositories());
            for (OrionDocument.Repository repository : entries) {
                if (!repository.id().equals(address.repositoryId())) continue;
                if (!repository.storage().equals(Optional.of(storage))) throw new Conflict();
                return document;
            }
            if (repositories.exists(address.toString())) throw new Conflict();
            entries.add(new OrionDocument.Repository(address.repositoryId(), "",
                    OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(), List.of(), List.of(),
                    List.of(), List.of(), Optional.of(storage)));
            teams.add(new OrionDocument.Team(team.id(), team.displayName(), team.grants(), team.roles(), entries));
        }
        if (!found) throw new IllegalArgumentException();
        return replaceOrganization(document, new OrionDocument.Organization(organization.id(),
                organization.displayName(), organization.users(), organization.grants(), organization.roles(), teams,
                organization.secrets(), organization.oidcProviders(), organization.invitations(), organization.connections()));
    }

    private static Optional<S3StorageBinding> binding(OrionDocument document, String name) {
        for (OrionDocument.Organization organization : document.organizations()) {
            for (OrionDocument.Team team : organization.teams()) {
                for (OrionDocument.Repository repository : team.repositories()) {
                    if (new RepositoryAddress(organization.id(), team.id(), repository.id()).toString().equals(name)) {
                        return repository.storage();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OrionDocument encrypt(OrionDocument document, Optional<OrganizationId> owner, String id, char[] value) {
        return owner.isEmpty() ? secrets.createSystem(document, id, value)
                : secrets.create(document, ConfigurationScope.organization(owner.orElseThrow()), id, value);
    }

    private static List<Connection> connections(OrionDocument document, Optional<OrganizationId> owner) {
        return owner.isEmpty() ? document.system().connections() : organization(document, owner.orElseThrow()).connections();
    }

    private static OrionDocument.Organization organization(OrionDocument document, OrganizationId id) {
        for (OrionDocument.Organization organization : document.organizations()) {
            if (organization.id().equals(id)) return organization;
        }
        throw new IllegalArgumentException();
    }

    private static OrionDocument withConnections(OrionDocument document, Optional<OrganizationId> owner,
            List<Connection> connections) {
        if (owner.isEmpty()) {
            OrionDocument.SystemConfiguration system = document.system();
            return new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(), system.https(),
                    system.secrets(), system.proxies(), connections), document.organizations());
        }
        OrionDocument.Organization organization = organization(document, owner.orElseThrow());
        return replaceOrganization(document, new OrionDocument.Organization(organization.id(),
                organization.displayName(), organization.users(), organization.grants(), organization.roles(),
                organization.teams(), organization.secrets(), organization.oidcProviders(), organization.invitations(),
                connections));
    }

    private static OrionDocument replaceOrganization(OrionDocument document, OrionDocument.Organization replacement) {
        List<OrionDocument.Organization> organizations = new ArrayList<>();
        for (OrionDocument.Organization organization : document.organizations()) {
            organizations.add(organization.id().equals(replacement.id()) ? replacement : organization);
        }
        return new OrionDocument(document.system(), organizations);
    }

    private static void require(boolean allowed) {
        if (!allowed) throw new SecurityException();
    }

    private static <T> Outcome<T> safely(Supplier<Outcome<T>> operation) {
        try {
            return operation.get();
        } catch (SecurityException denied) {
            return new Failure<>(FailureCode.DENIED, "Access denied");
        } catch (OrionConfigurationConcurrentUpdateException | Conflict conflict) {
            return new Failure<>(FailureCode.CONFLICT, "Configuration or binding changed; reload before retrying");
        } catch (IllegalArgumentException invalid) {
            return new Failure<>(FailureCode.INVALID, "Invalid storage request or unavailable organization/team");
        } catch (RuntimeException unavailable) {
            return new Failure<>(FailureCode.UNAVAILABLE, "Storage management is unavailable");
        }
    }

    private static final class Conflict extends RuntimeException {}
}
