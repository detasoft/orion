package pro.deta.orion.transport.git;

import pro.deta.orion.acl.RootPasswordReset;

import pro.deta.orion.config.OrionConfigurationEditor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.*;
import pro.deta.orion.auth.*;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.transport.git.auth.AuthenticatedRepositoryAccessHook;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.bootstrap.config.*;
import pro.deta.orion.schema.orion.*;
import pro.deta.orion.schema.orion.v2.*;
import pro.deta.orion.util.Result;

import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.internal.ChangeMetadata;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredStorageManagementTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private final MemoryStorage storage = new MemoryStorage();
    private final OrganizationId organization = new OrganizationId("acme");
    private final NativeGitRepositoryProvider repositories =
            NativeGitRepositoryProvider.inMemory(testRuntime.events());
    private OrionKeyMaterial material;
    private ConfiguredStorageManagement management;
    private OrionConfigurationEditor editor;
    private OrionAccessControlServiceImpl acl;
    private SecurityContext actor;

    @BeforeEach
    void setUp() throws Exception {
        material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(),
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                testRuntime.unstartedEvents());
        User user = new User("alice", "", "", "", List.of(), List.of(), List.of(
                grant(GrantKey.CONNECTION, "*", GrantKey.CREATE),
                grant(GrantKey.CONNECTION, "*", GrantKey.READ_WRITE),
                grant(GrantKey.CONNECTION, "*", GrantKey.CONNECTION_USE),
                grant(GrantKey.REPOSITORY, "acme/**", GrantKey.CREATE)));
        OrionDocument.Organization org = new OrionDocument.Organization(organization, "Acme", List.of(user),
                List.of(), List.of(), List.of(new OrionDocument.Team(new TeamId("team"), "Team", List.of(),
                List.of(), List.of())), List.of(), List.of(), List.of(), List.of());
        storage.set(new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(org)));
        editor = new OrionConfigurationEditor(storage,
                material,
                testRuntime.events());
        acl =
                new OrionAccessControlServiceImpl(
                        new OrionPasswordHashingService(),
                        new RootPasswordReset(false),
                        OrionKeyMaterial.unavailable(),
                        new SigningMaterialSet(new KeyMaterialDescriptor("signing",
                                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA,
                                1), List.of()),
                        new BootstrapConfiguration(),
                        editor);
        editor.onStart();
        acl.onStart();
        management = new ConfiguredStorageManagement(acl, editor, material, repositories);
        actor = SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("alice", organization, () -> editor.current().result()
                        .valueOrFailure("Cannot read configuration document")));
    }

    @AfterEach
    void close() {
        acl.onStop();
        editor.onStop();
        repositories.close();
        material.close();
    }

    @Test
    void persistsEncryptedConnectionAndClearsInputWithoutExposingSecrets() {
        char[] secret = "private-access-secret".toCharArray();
        StorageManagement.Outcome<StorageManagement.Connections> result = management.saveConnection(actor,
                Optional.of(organization), revision(), true, input(secret));
        testRuntime.awaitEvents();
        assertThat(result).isInstanceOf(StorageManagement.Success.class);
        assertThat(secret).containsOnly('\0');
        assertThat(storage.snapshot.result().valueOrFailure("Cannot read configuration document").toString())
                .doesNotContain("private-access-secret");
        Connection.S3 connection = (Connection.S3) activeDocument().organizations()
                .getFirst().connections().getFirst();
        char[] resolved = new ConfigurationSecrets(() -> editor.current().result()
                .valueOrFailure("Cannot read configuration document"), material)
                .resolveOrganization(activeDocument(), organization, connection.secretKey().orElseThrow());
        assertThat(resolved).isEqualTo("private-access-secret".toCharArray());
        Arrays.fill(resolved, '\0');
        assertThat(result.toString()).doesNotContain("private-access-secret", connection.secretKey().orElseThrow());
    }

    @Test
    void deniesForeignOrganizationDefaultCredentialsAndStaleRevisionWithoutMutation() {
        assertFailure(management.saveConnection(actor, Optional.of(new OrganizationId("other")), revision(),
                true, input("secret".toCharArray())), StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
        assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), true,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, null, null, null, true)),
                StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
        assertFailure(management.saveConnection(actor, Optional.of(organization), "stale", true,
                input("secret".toCharArray())), StorageManagement.FailureCode.CONFLICT);
        testRuntime.awaitEvents();
        assertThat(storage.saves).isZero();
    }

    @Test
    void localCreationRetainsDuplicateBehavior() {
        assertThat(management.createRepository(actor, "acme/team/local", Optional.empty()))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(true)));
        testRuntime.awaitEvents();
        assertThat(management.createRepository(actor, "acme/team/local", Optional.empty()))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(false)));
        testRuntime.awaitEvents();
        assertThat(activeDocument().organizations().getFirst().teams().getFirst().repositories())
                .extracting(OrionDocument.Repository::id).containsExactly("local");
    }

    @Test
    void localCreationRegistersScopeWithoutGrantingSiblingAccess() throws Exception {
        setGrants(List.of(grant(GrantKey.REPOSITORY, "acme/team/local", GrantKey.CREATE),
                grant(GrantKey.REPOSITORY, "acme/team/local", GrantKey.READ_WRITE)), true);
        pro.deta.orion.auth.check.resource.RepositoryResource resource =
                pro.deta.orion.auth.check.resource.RepositoryResource.of("acme/team/local");
        assertThat(pro.deta.orion.auth.check.rule.RepositoryAccessRules.read()
                .evaluate(actor, resource).allowed()).isFalse();

        assertThat(management.createRepository(actor, "acme/team/local", Optional.empty()))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(true)));
        testRuntime.awaitEvents();

        assertThat(pro.deta.orion.auth.check.rule.RepositoryAccessRules.read()
                .evaluate(actor, resource).allowed()).isTrue();
        assertThat(pro.deta.orion.auth.check.rule.RepositoryAccessRules.write()
                .evaluate(actor, resource).allowed()).isTrue();
        assertThat(pro.deta.orion.auth.check.rule.RepositoryAccessRules.read().evaluate(actor,
                pro.deta.orion.auth.check.resource.RepositoryResource.of("acme/team/sibling")).allowed())
                .isFalse();
        assertThat(activeDocument().organizations().getFirst().teams().getFirst().repositories())
                .singleElement().satisfies(repository -> {
                    assertThat(repository.id()).isEqualTo("local");
                    assertThat(repository.storage()).isEmpty();
                    assertThat(repository.grants()).isEmpty();
                    assertThat(repository.roles()).isEmpty();
                });
    }

    @Test
    void firstPushRegistersBeforePublishingAndAllowsSubsequentRead() throws Exception {
        setGrants(List.of(grant(GrantKey.REPOSITORY, "acme/team/local", GrantKey.CREATE),
                grant(GrantKey.REPOSITORY, "acme/team/local", GrantKey.READ_WRITE)), true);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(repositories);
        AuthenticatedRepositoryAccessHook hook = new AuthenticatedRepositoryAccessHook(actor);
        try (GitRepositoryContext context = service.open(
                GitWireBootstrap.sshCommandData("git-receive-pack 'acme/team/local'", null), hook,
                Optional.empty(), () -> management.prepareRepository(actor, "acme/team/local"))) {
            NativeGitRepository repository = repositories.openForWrite("acme/team/local")
                    .valueOrFailure("repository");
            ObjectId object = repository.writeObject(GitObjectType.BLOB, new byte[]{1});
            assertThat(context.publish(Optional.empty(), List.of(RefUpdate.fromWire("refs/heads/main",
                    "0".repeat(40), object.toHex())), false)).singleElement()
                    .satisfies(result -> assertThat(result.status()).isEqualTo(
                            RefUpdateResult.Status.APPLIED));
        }
        try (GitRepositoryContext ignored = service.open(
                GitWireBootstrap.sshCommandData("git-upload-pack 'acme/team/local'", null), hook)) {
            assertThat(repositories.exists("acme/team/local")).isTrue();
        }
        for (String denied : List.of("other/team/local", "acme/missing/local", "acme/team/sibling")) {
            assertFailure(management.prepareRepository(actor, denied), StorageManagement.FailureCode.DENIED);
            assertThat(repositories.exists(denied)).isFalse();
        }
    }

    @Test
    void localStorageFailureRetainsRegistrationForAnIdenticalRetry() {
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (NativeGitRepositoryProvider observed = new NativeGitRepositoryProvider(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events())) {
            @Override
            public Result<NativeGitRepository> create(String name) {
                assertThat(activeDocument().organizations().getFirst().teams().getFirst().repositories())
                        .extracting(OrionDocument.Repository::id).containsExactly("local");
                if (failed.get()) return new Result.Failure<>(Result.FailureCode.GENERAL);
                return super.create(name);
            }
        }) {
            ConfiguredStorageManagement operation = new ConfiguredStorageManagement(acl, editor, material, observed);
            assertFailure(operation.createRepository(actor, "acme/team/local", Optional.empty()),
                    StorageManagement.FailureCode.STORAGE_RETRY);
            failed.set(false);
            assertThat(operation.createRepository(actor, "acme/team/local", Optional.empty()))
                    .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(true)));
            assertThat(operation.createRepository(actor, "acme/team/local", Optional.empty()))
                    .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(false)));
            assertThat(storage.saves).isEqualTo(1);
        }
    }

    @Test
    void replacementUsesNewSecretAndOmittedFieldsPreserveValues() throws Exception {
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), true,
                input("original-secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        OrionDocument before = activeDocument();
        Connection.S3 original = (Connection.S3) before.organizations().getFirst().connections().getFirst();
        String oldReference = original.secretKey().orElseThrow();
        OrionDocument.Organization org = before.organizations().getFirst();
        Connection.S3 shared = new Connection.S3("shared", original.endpoint(), original.region(),
                original.pathStyleAccess(), original.accessKeyId(), original.secretKey(), original.sessionToken());
        storage.set(new OrionDocument(before.system(), List.of(new OrionDocument.Organization(org.id(),
                org.displayName(), org.users(), org.grants(), org.roles(), org.teams(), org.secrets(),
                org.oidcProviders(), org.invitations(), List.of(original, shared)))));
        testRuntime.awaitEvents();
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("replacement-secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        OrionDocument replaced = activeDocument();
        Connection.S3 replacement = (Connection.S3) OrionDocument.findConnection(
                replaced.organizations().getFirst().connections(), "archive");
        assertThat(OrionDocument.findConnection(replaced.organizations().getFirst().connections(), "shared"))
                .isEqualTo(shared);
        assertThat(replacement.secretKey().orElseThrow()).isNotEqualTo(oldReference);
        assertThat(new ConfigurationSecrets(() -> replaced, material)
                .resolveOrganization(replaced, organization, oldReference)).isEqualTo("original-secret".toCharArray());
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), false,
                new StorageManagement.S3Input("archive", null, "eu-west-1", false, null, null, null, false)))
                .isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        Connection.S3 kept = (Connection.S3) OrionDocument.findConnection(
                activeDocument().organizations().getFirst().connections(), "archive");
        assertThat(kept.secretKey()).isEqualTo(replacement.secretKey());
        assertThat(kept.accessKeyId()).contains("access-id");
        assertThat(activeDocument().organizations().getFirst().users())
                .isEqualTo(before.organizations().getFirst().users());
        assertThat(activeDocument().organizations().getFirst().teams())
                .isEqualTo(before.organizations().getFirst().teams());
    }

    @Test
    void omittedSessionTokenPreservesItAndExplicitEmptyTokenClearsOnlyItsReference() {
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), true,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, "id",
                        "secret".toCharArray(), "session-secret".toCharArray(), false)))
                .isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        Connection.S3 original = (Connection.S3) activeDocument().organizations()
                .getFirst().connections().getFirst();
        management.saveConnection(actor, Optional.of(organization), revision(), false,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, null, null, null, false));
        testRuntime.awaitEvents();
        assertThat(((Connection.S3) activeDocument().organizations().getFirst().connections()
                .getFirst()).sessionToken()).isEqualTo(original.sessionToken());
        management.saveConnection(actor, Optional.of(organization), revision(), false,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, null, null, new char[0], false));
        testRuntime.awaitEvents();
        Connection.S3 cleared = (Connection.S3) activeDocument().organizations()
                .getFirst().connections().getFirst();
        assertThat(cleared.sessionToken()).isEmpty();
        assertThat(cleared.secretKey()).isEqualTo(original.secretKey());
    }

    @Test
    void creationChangeAndUseAreIndependentAndRevocationIsCheckedAgainstStoredDocument() throws Exception {
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), true,
                input("secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        OrionDocument old = activeDocument();
        setGrants(List.of(grant(GrantKey.CONNECTION, "*", GrantKey.CREATE)), false);
        assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("replacement".toCharArray())), StorageManagement.FailureCode.CONFLICT);
        testRuntime.awaitEvents();
        // A matching revision from storage with a stale identity still cannot authorize the revoked change.
        assertFailure(management.saveConnection(actor, Optional.of(organization),
                ((OrionRevision.Stored) storage.snapshot.revision()).value(), false,
                input("replacement".toCharArray())), StorageManagement.FailureCode.DENIED);
        storage.publish();
        assertThat(((StorageManagement.Success<StorageManagement.Connections>) management.connections(
                actor, Optional.of(organization))).value().connections()).isEmpty();
        setGrants(List.of(grant(GrantKey.CONNECTION, "*", GrantKey.CONNECTION_USE)), true);
        StorageManagement.Connections view = ((StorageManagement.Success<StorageManagement.Connections>)
                management.connections(actor, Optional.of(organization))).value();
        assertThat(view.connections()).hasSize(1);
        assertThat(view.connections().getFirst().canUse()).isTrue();
        assertThat(view.connections().getFirst().canChange()).isFalse();
        assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("replacement".toCharArray())), StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
        assertFailure(management.createRepository(actor, "acme/team/new", Optional.of(s3Binding())),
                StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
    }

    @Test
    void falseConnectionUseGrantDoesNotAuthorizeUsingConnection() throws Exception {
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), true,
                input("secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        setGrants(List.of(new Grant("connection-use", List.of(
                new GrantExpression(GrantKey.CONNECTION, "*"),
                new GrantExpression(GrantKey.READ, "true"),
                new GrantExpression(GrantKey.CONNECTION_USE, "false")))), true);

        StorageManagement.Connections connections = ((StorageManagement.Success<StorageManagement.Connections>)
                management.connections(actor, Optional.of(organization))).value();
        assertThat(connections.connections()).hasSize(1);
        assertThat(connections.connections().getFirst().canUse()).isFalse();
    }

    @Test
    void metadataFailureRetainsBindingAndIdenticalRetryCompletesWithoutLocalFallback() {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        testRuntime.awaitEvents();
        java.util.concurrent.atomic.AtomicInteger fail = new java.util.concurrent.atomic.AtomicInteger(1);
        pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider observed =
                new pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider(
                        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(
                                testRuntime.events())) {
                    @Override
                    public void close() {
                        repositories.close();
                    }

                    @Override
                    public List<String> repositoryNames() {
                        return repositories.repositoryNames();
                    }

                    @Override
                    public boolean exists(String name) {
                        return repositories.exists(name);
                    }

                    @Override
                    public Result<pro.deta.orion.git.nativestorage.NativeGitRepository> find(
                            String name) {
                        return repositories.find(name);
                    }

                    @Override
                    public Result<pro.deta.orion.git.nativestorage.NativeGitRepository> create(
                            String name) {
                        assertThat(
                                        activeDocument()
                                                .organizations()
                                                .getFirst()
                                                .teams()
                                                .getFirst()
                                                .repositories()
                                                .getFirst()
                                                .storage())
                                .contains(s3Binding());
                        if (fail.get() == 1)
                            return new Result.Failure<>(
                                    Result.FailureCode.GENERAL, "credential-should-not-leak");
                        if (fail.get() == 2)
                            throw new IllegalStateException("credential-should-not-leak");
                        return repositories.create(name);
                    }
                };
        ConfiguredStorageManagement operation = new ConfiguredStorageManagement(acl, editor, material, observed);
        StorageManagement.Outcome<?> failed = operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding()));
        testRuntime.awaitEvents();
        assertFailure(failed, StorageManagement.FailureCode.STORAGE_RETRY);
        assertThat(failed.toString()).doesNotContain("credential-should-not-leak");
        assertThat(repositories.exists("acme/team/archive")).isFalse();
        fail.set(2);
        assertFailure(operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())),
                StorageManagement.FailureCode.STORAGE_RETRY);
        testRuntime.awaitEvents();
        fail.set(0);
        assertThat(operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(true)));
        testRuntime.awaitEvents();
        assertThat(operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(false)));
        testRuntime.awaitEvents();
        assertFailure(operation.createRepository(actor, "acme/team/archive", Optional.of(new S3StorageBinding(
                s3Binding().connection(), java.net.URI.create("s3://other-bucket/prefix")))),
                StorageManagement.FailureCode.CONFLICT);
        testRuntime.awaitEvents();
        assertFailure(operation.createRepository(actor, "acme/team/archive", Optional.empty()),
                StorageManagement.FailureCode.CONFLICT);
        testRuntime.awaitEvents();
    }

    @Test
    void rejectsOverlongMultibytePrefixBeforePersistingBinding() {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        testRuntime.awaitEvents();
        int saves = storage.saves;
        S3StorageBinding invalid = new S3StorageBinding(s3Binding().connection(),
                java.net.URI.create("s3://test-bucket/" + "é".repeat(500)));
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(invalid)),
                StorageManagement.FailureCode.INVALID);
        testRuntime.awaitEvents();
        assertThat(storage.saves).isEqualTo(saves);
        assertThat(repositories.repositoryNames()).isEmpty();
    }

    @Test
    void sshDispatcherUsesSharedCreationAndRejectsCredentialParameters() {
        pro.deta.orion.command.CommandNode tree = pro.deta.orion.command.CommandNode.builder()
                .child("repository", pro.deta.orion.command.CommandNode.builder()
                        .action(new pro.deta.orion.transport.git.command.RepositoryCreationCommand(management).definition())
                        .build()).build();
        pro.deta.orion.command.DefaultCommandDispatcher dispatcher = new pro.deta.orion.command.DefaultCommandDispatcher(
                new pro.deta.orion.command.CommandLineParser(), tree, new pro.deta.orion.command.CommandRowQuery());
        pro.deta.orion.command.CommandContext context = new pro.deta.orion.command.CommandContext(actor, "request",
                "session", "source", pro.deta.orion.command.CommandPath.root(),
                pro.deta.orion.command.CommandPresentation.plain(), pro.deta.orion.command.CommandCancellation.never(),
                Map.of());
        assertThat(dispatcher.dispatch(new pro.deta.orion.command.CommandRequest("repository create acme/team/ssh", context)))
                .isEqualTo(new pro.deta.orion.command.CommandResult.Message("Repository created"));
        assertThat(repositories.exists("acme/team/ssh")).isTrue();
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        testRuntime.awaitEvents();
        assertThat(dispatcher.dispatch(new pro.deta.orion.command.CommandRequest(
                "repository create acme/team/s3 connection=organization/archive location=s3://test-bucket/prefix", context)))
                .isEqualTo(new pro.deta.orion.command.CommandResult.Message("Repository created"));
        assertThat(activeDocument().organizations().getFirst().teams().getFirst()
                .repositories()).filteredOn(repository -> repository.id().equals("s3"))
                .singleElement().satisfies(repository -> assertThat(repository.storage()).contains(s3Binding()));
        assertThat(dispatcher.dispatch(new pro.deta.orion.command.CommandRequest(
                "repository create acme/team/forbidden secret-key=secret", context)))
                .isInstanceOf(pro.deta.orion.command.CommandResult.Failure.class);
        assertThat(repositories.exists("acme/team/forbidden")).isFalse();
    }

    @Test
    void scopedDenyWinsAndRepositoryCreateWithoutConnectionUseHasNoSideEffects() throws Exception {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        testRuntime.awaitEvents();
        OrionDocument document = activeDocument();
        OrionDocument.Organization org = document.organizations().getFirst();
        ConfigurationScope scope = ConfigurationScope.organization(organization);
        ScopedGrant deny = new ScopedGrant(new GrantId("deny-use"), ScopedGrant.Effect.DENY,
                grant(GrantKey.CONNECTION, "archive", GrantKey.CONNECTION_USE).info());
        ScopedRole role = new ScopedRole(new RoleId("denied"), List.of(),
                List.of(new GrantAddress(scope, deny.id())));
        User user = new User("alice", "", "", "", List.of(),
                List.of("acme/denied"), org.users().getFirst().grants());
        storage.set(new OrionDocument(document.system(), List.of(new OrionDocument.Organization(org.id(), "",
                List.of(user), List.of(deny), List.of(role), org.teams(), org.secrets(), org.oidcProviders(),
                org.invitations(), org.connections()))));
        testRuntime.awaitEvents();
        int saves = storage.saves;
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())),
                StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
        assertThat(storage.saves).isEqualTo(saves);
        assertThat(repositories.repositoryNames()).isEmpty();
        StorageManagement.Connections view = ((StorageManagement.Success<StorageManagement.Connections>)
                management.connections(actor, Optional.of(organization))).value();
        assertThat(view.connections().getFirst().canUse()).isFalse();
        assertThat(view.connections().getFirst().canChange()).isTrue();
    }

    @Test
    void matchingCreateAndChangeDeniesOverrideAllowsAndKeepSecretsUnchanged() throws Exception {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("original".toCharArray()));
        testRuntime.awaitEvents();
        for (GrantKey action : List.of(GrantKey.CREATE, GrantKey.READ_WRITE)) {
            OrionDocument document = activeDocument();
            OrionDocument.Organization org = document.organizations().getFirst();
            ScopedGrant deny = new ScopedGrant(new GrantId("deny-change"), ScopedGrant.Effect.DENY,
                    grant(GrantKey.CONNECTION, "archive*", action).info());
            ScopedRole role = new ScopedRole(new RoleId("denied"), List.of(), List.of(new GrantAddress(
                    ConfigurationScope.organization(organization), deny.id())));
            User user = new User("alice", "", "", "", List.of(), List.of("acme/denied"),
                    org.users().getFirst().grants());
            storage.set(new OrionDocument(document.system(), List.of(new OrionDocument.Organization(org.id(), "",
                    List.of(user), List.of(deny), List.of(role), org.teams(), org.secrets(), org.oidcProviders(),
                    org.invitations(), org.connections()))));
            testRuntime.awaitEvents();
            int saves = storage.saves;
            boolean create = action == GrantKey.CREATE;
            char[] secret = "replacement".toCharArray();
            assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), create,
                    new StorageManagement.S3Input(create ? "archive-new" : "archive", null, "us-east-1", true,
                            "access-id", secret, null, false)), StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
            assertThat(secret).containsOnly('\0');
            assertThat(storage.saves).isEqualTo(saves);
            assertThat(activeDocument().organizations().getFirst().secrets()).isEqualTo(org.secrets());
        }
    }

    @Test
    void repositoryReaderNeedsNoConnectionUseAndExistingLocalRepositoryCannotBeRebound() throws Exception {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        testRuntime.awaitEvents();
        management.createRepository(actor, "acme/team/local", Optional.empty());
        testRuntime.awaitEvents();
        assertFailure(management.createRepository(actor, "acme/team/local", Optional.of(s3Binding())),
                StorageManagement.FailureCode.CONFLICT);
        testRuntime.awaitEvents();
        management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding()));
        testRuntime.awaitEvents();
        setGrants(List.of(grant(GrantKey.REPOSITORY, "acme/team/archive", GrantKey.READ)), true);
        assertThat(pro.deta.orion.auth.check.rule.RepositoryAccessRules.read().evaluate(actor,
                pro.deta.orion.auth.check.resource.RepositoryResource.of("acme/team/archive")).allowed()).isTrue();
        assertThat(((StorageManagement.Success<StorageManagement.Connections>) management.connections(actor,
                Optional.of(organization))).value().connections()).isEmpty();
    }

    @Test
    void systemAndDefaultCredentialsRequireCurrentSystemAdministrator() throws Exception {
        OrionDocument document = activeDocument();
        User adminUser = new User("operator", "", "", "", List.of(), List.of(),
                List.of(new Grant("admin", List.of(
                        new GrantExpression(GrantKey.ADMIN, "true")))));
        storage.set(new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(List.of(adminUser), List.of(), List.of())), document.organizations()));
        testRuntime.awaitEvents();
        SecurityContext admin = SecurityContext.createContext().withUserIdentity(new InternalUserImpl("operator", List.of()));
        StorageManagement.S3Input defaults = new StorageManagement.S3Input("archive", null, "us-east-1", false,
                null, null, null, true);
        assertThat(management.saveConnection(admin, Optional.empty(), revision(), true, defaults))
                .isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        assertFailure(management.connections(actor, Optional.empty()), StorageManagement.FailureCode.DENIED);
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(new S3StorageBinding(
                new ConnectionReference(ConnectionReference.Scope.SYSTEM, "archive"), s3Binding().location()))),
                StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
        assertThat(management.saveConnection(admin, Optional.of(organization), revision(), true, defaults))
                .isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())),
                StorageManagement.FailureCode.DENIED);
        testRuntime.awaitEvents();
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("own-explicit-secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        testRuntime.awaitEvents();
        assertThat(((StorageManagement.Success<StorageManagement.Connections>) management.connections(
                actor, Optional.of(organization))).value().connections().getFirst().canUse()).isTrue();
    }

    @Test
    void preparingConfiguredS3RepositoryPreservesItsBindingWithoutConnectionUse() throws Exception {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        testRuntime.awaitEvents();
        management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding()));
        testRuntime.awaitEvents();
        setGrants(List.of(grant(GrantKey.REPOSITORY, "acme/team/archive", GrantKey.CREATE),
                grant(GrantKey.REPOSITORY, "acme/team/archive", GrantKey.READ_WRITE)), true);
        assertThat(management.prepareRepository(actor, "acme/team/archive"))
                .isEqualTo(new StorageManagement.Success<Void>(null));
        assertThat(activeDocument().organizations().getFirst().teams().getFirst().repositories())
                .singleElement().satisfies(repository -> assertThat(repository.storage()).contains(s3Binding()));
    }

    private S3StorageBinding s3Binding() {
        return new S3StorageBinding(new ConnectionReference(ConnectionReference.Scope.ORGANIZATION, "archive"),
                java.net.URI.create("s3://test-bucket/prefix"));
    }

    private void setGrants(List<Grant> grants, boolean reload) throws Exception {
        OrionDocument document = activeDocument();
        OrionDocument.Organization org = document.organizations().getFirst();
        User user = new User("alice", "", "", "", List.of(), List.of(), grants);
        storage.set(new OrionDocument(document.system(), List.of(new OrionDocument.Organization(org.id(),
                org.displayName(), List.of(user), org.grants(), org.roles(), org.teams(), org.secrets(),
                org.oidcProviders(), org.invitations(), org.connections()))), reload);

    }

    private String revision() {
        testRuntime.awaitEvents();
        return ((OrionRevision.Stored) editor.current().revision()).value();
    }

    private OrionDocument activeDocument() {
        testRuntime.awaitEvents();
        return editor.current().result().valueOrFailure("Cannot read configuration document");
    }

    private StorageManagement.S3Input input(char[] secret) {
        return new StorageManagement.S3Input("archive", "http://localhost:9000", "us-east-1", true,
                "access-id", secret, null, false);
    }

    private static Grant grant(GrantKey selector, String name,
            GrantKey action) {
        return new Grant(selector + "-" + action, List.of(
                new GrantExpression(selector, name), new GrantExpression(action, "true")));
    }

    private static void assertFailure(StorageManagement.Outcome<?> result, StorageManagement.FailureCode code) {
        assertThat(result).isInstanceOfSatisfying(StorageManagement.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private final class MemoryStorage implements OrionVersionedConfigurationStorage {
        private OrionDocumentRevision snapshot;
        private int saves;
        private int replacements;

        void set(OrionDocument document) {
            set(document, true);
        }

        void set(OrionDocument document, boolean publish) {
            snapshot =
                    new OrionDocumentRevision(
                            Result.of(document), new OrionRevision.Stored("fixture-" + replacements++));
            if (publish) publish();
        }

        private void publish() {
            testRuntime.events().publishAndWait(new ConfigurationChanged(this, snapshot));
        }

        @Override
        public OrionDocumentRevision load() {
            return snapshot;
        }

        @Override
        public OrionDocumentRevision load(OrionRevision.Stored revision) {
            return snapshot.revision().equals(revision)
                    ? load()
                    : new OrionDocumentRevision(
                            new Result.Failure<>(Result.FailureCode.NOT_FOUND),
                            new OrionRevision.Absent());
        }

        @Override
        public synchronized OrionRevision.Stored save(
                OrionRevision expected, OrionDocument document, ChangeMetadata metadata) {
            if (!snapshot.revision().equals(expected)) {
                throw new OrionConfigurationConcurrentUpdateException("Changed", null);
            }
            if (snapshot.revision() instanceof OrionRevision.Stored stored && snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .equals(document)) return stored;
            OrionRevision.Stored saved = new OrionRevision.Stored("v" + ++saves);
            snapshot = new OrionDocumentRevision(Result.of(document), saved);
            testRuntime.events().publish(new ConfigurationChanged(this, snapshot));
            return saved;
        }
    }
}
