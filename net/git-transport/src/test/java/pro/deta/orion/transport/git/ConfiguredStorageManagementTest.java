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
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    private final MemoryStorage storage = new MemoryStorage();
    private final OrganizationId organization = new OrganizationId("acme");
    private final NativeGitRepositoryProvider repositories =
            NativeGitRepositoryProvider.inMemory(EVENTS.manager());
    private KeyMaterialService material;
    private ConfigurationCipherCapability cipher;
    private ConfiguredStorageManagement management;
    private OrionConfigurationEditor editor;
    private OrionAccessControlServiceImpl acl;
    private SecurityContext actor;

    @BeforeEach
    void setUp() throws Exception {
        KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES,
                new KeyMaterialVersion(1), KeyMaterialScope.cluster("test"));
        material = KeyMaterialService.open(new InMemoryKeyMaterialContentStore(),
                KeyMaterialOptions.pkcs12("test-password".toCharArray()));
        material.generateSecretKeyIfMissing(descriptor, 256);
        cipher = KeyMaterialCapabilities.open(material, List.of(descriptor)).configurationCipher(descriptor);
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
                new BootstrapConfiguration(),
                cipher,
                ConfigurationMaterialCapability.unavailable(),
                EVENTS.manager());
        acl =
                new OrionAccessControlServiceImpl(
                        new OrionPasswordHashingService(),
                        new RootPasswordReset(false),
                        ServerIdentityCapability.unavailable(),
                        new BootstrapConfiguration(),
                        editor);
        acl.onStart();
        management = new ConfiguredStorageManagement(acl, editor, cipher, repositories);
        actor = SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("alice", organization, () -> acl.configuration().result()
                        .valueOrFailure("Cannot read configuration document")));
    }

    @AfterEach
    void close() { acl.onStop(); repositories.close(); material.close(); }

    @Test
    void persistsEncryptedConnectionAndClearsInputWithoutExposingSecrets() {
        char[] secret = "private-access-secret".toCharArray();
        StorageManagement.Outcome<StorageManagement.Connections> result = management.saveConnection(actor,
                Optional.of(organization), revision(), true, input(secret));
        EVENTS.await();
        assertThat(result).isInstanceOf(StorageManagement.Success.class);
        assertThat(secret).containsOnly('\0');
        assertThat(storage.snapshot.result().valueOrFailure("Cannot read configuration document").toString())
                .doesNotContain("private-access-secret");
        Connection.S3 connection = (Connection.S3) activeDocument().organizations()
                .getFirst().connections().getFirst();
        char[] resolved = new ConfigurationSecrets(() -> acl.configuration().result()
                .valueOrFailure("Cannot read configuration document"), cipher)
                .resolveOrganization(activeDocument(), organization, connection.secretKey().orElseThrow());
        assertThat(resolved).isEqualTo("private-access-secret".toCharArray());
        Arrays.fill(resolved, '\0');
        assertThat(result.toString()).doesNotContain("private-access-secret", connection.secretKey().orElseThrow());
    }

    @Test
    void deniesForeignOrganizationDefaultCredentialsAndStaleRevisionWithoutMutation() {
        assertFailure(management.saveConnection(actor, Optional.of(new OrganizationId("other")), revision(),
                true, input("secret".toCharArray())), StorageManagement.FailureCode.DENIED);
        EVENTS.await();
        assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), true,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, null, null, null, true)),
                StorageManagement.FailureCode.DENIED);
        EVENTS.await();
        assertFailure(management.saveConnection(actor, Optional.of(organization), "stale", true,
                input("secret".toCharArray())), StorageManagement.FailureCode.CONFLICT);
        EVENTS.await();
        assertThat(storage.saves).isZero();
    }

    @Test
    void localCreationRetainsDuplicateBehavior() {
        assertThat(management.createRepository(actor, "acme/team/local", Optional.empty()))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(true)));
        EVENTS.await();
        assertThat(management.createRepository(actor, "acme/team/local", Optional.empty()))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(false)));
        EVENTS.await();
    }

    @Test
    void replacementUsesNewSecretAndOmittedFieldsPreserveValues() throws Exception {
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), true,
                input("original-secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
        OrionDocument before = activeDocument();
        Connection.S3 original = (Connection.S3) before.organizations().getFirst().connections().getFirst();
        String oldReference = original.secretKey().orElseThrow();
        OrionDocument.Organization org = before.organizations().getFirst();
        Connection.S3 shared = new Connection.S3("shared", original.endpoint(), original.region(),
                original.pathStyleAccess(), original.accessKeyId(), original.secretKey(), original.sessionToken());
        storage.set(new OrionDocument(before.system(), List.of(new OrionDocument.Organization(org.id(),
                org.displayName(), org.users(), org.grants(), org.roles(), org.teams(), org.secrets(),
                org.oidcProviders(), org.invitations(), List.of(original, shared)))));
        EVENTS.await();
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("replacement-secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
        OrionDocument replaced = activeDocument();
        Connection.S3 replacement = (Connection.S3) OrionDocument.findConnection(
                replaced.organizations().getFirst().connections(), "archive");
        assertThat(OrionDocument.findConnection(replaced.organizations().getFirst().connections(), "shared"))
                .isEqualTo(shared);
        assertThat(replacement.secretKey().orElseThrow()).isNotEqualTo(oldReference);
        assertThat(new ConfigurationSecrets(() -> replaced, cipher)
                .resolveOrganization(replaced, organization, oldReference)).isEqualTo("original-secret".toCharArray());
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), false,
                new StorageManagement.S3Input("archive", null, "eu-west-1", false, null, null, null, false)))
                .isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
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
        EVENTS.await();
        Connection.S3 original = (Connection.S3) activeDocument().organizations()
                .getFirst().connections().getFirst();
        management.saveConnection(actor, Optional.of(organization), revision(), false,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, null, null, null, false));
        EVENTS.await();
        assertThat(((Connection.S3) activeDocument().organizations().getFirst().connections()
                .getFirst()).sessionToken()).isEqualTo(original.sessionToken());
        management.saveConnection(actor, Optional.of(organization), revision(), false,
                new StorageManagement.S3Input("archive", null, "us-east-1", true, null, null, new char[0], false));
        EVENTS.await();
        Connection.S3 cleared = (Connection.S3) activeDocument().organizations()
                .getFirst().connections().getFirst();
        assertThat(cleared.sessionToken()).isEmpty();
        assertThat(cleared.secretKey()).isEqualTo(original.secretKey());
    }

    @Test
    void creationChangeAndUseAreIndependentAndRevocationIsCheckedAgainstStoredDocument() throws Exception {
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), true,
                input("secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
        OrionDocument old = activeDocument();
        setGrants(List.of(grant(GrantKey.CONNECTION, "*", GrantKey.CREATE)), false);
        assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("replacement".toCharArray())), StorageManagement.FailureCode.CONFLICT);
        EVENTS.await();
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
        EVENTS.await();
        assertFailure(management.createRepository(actor, "acme/team/new", Optional.of(s3Binding())),
                StorageManagement.FailureCode.DENIED);
        EVENTS.await();
    }

    @Test
    void metadataFailureRetainsBindingAndIdenticalRetryCompletesWithoutLocalFallback() {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        EVENTS.await();
        java.util.concurrent.atomic.AtomicInteger fail = new java.util.concurrent.atomic.AtomicInteger(1);
        pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider observed =
                new pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider(
                        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(
                                EVENTS.manager())) {
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
        ConfiguredStorageManagement operation = new ConfiguredStorageManagement(acl, editor, cipher, observed);
        StorageManagement.Outcome<?> failed = operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding()));
        EVENTS.await();
        assertFailure(failed, StorageManagement.FailureCode.STORAGE_RETRY);
        assertThat(failed.toString()).doesNotContain("credential-should-not-leak");
        assertThat(repositories.exists("acme/team/archive")).isFalse();
        fail.set(2);
        assertFailure(operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())),
                StorageManagement.FailureCode.STORAGE_RETRY);
        EVENTS.await();
        fail.set(0);
        assertThat(operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(true)));
        EVENTS.await();
        assertThat(operation.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())))
                .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(false)));
        EVENTS.await();
        assertFailure(operation.createRepository(actor, "acme/team/archive", Optional.of(new S3StorageBinding(
                s3Binding().connection(), java.net.URI.create("s3://other-bucket/prefix")))),
                StorageManagement.FailureCode.CONFLICT);
        EVENTS.await();
        assertFailure(operation.createRepository(actor, "acme/team/archive", Optional.empty()),
                StorageManagement.FailureCode.CONFLICT);
        EVENTS.await();
    }

    @Test
    void rejectsOverlongMultibytePrefixBeforePersistingBinding() {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        EVENTS.await();
        int saves = storage.saves;
        S3StorageBinding invalid = new S3StorageBinding(s3Binding().connection(),
                java.net.URI.create("s3://test-bucket/" + "é".repeat(500)));
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(invalid)),
                StorageManagement.FailureCode.INVALID);
        EVENTS.await();
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
        EVENTS.await();
        assertThat(dispatcher.dispatch(new pro.deta.orion.command.CommandRequest(
                "repository create acme/team/s3 connection=organization/archive location=s3://test-bucket/prefix", context)))
                .isEqualTo(new pro.deta.orion.command.CommandResult.Message("Repository created"));
        assertThat(activeDocument().organizations().getFirst().teams().getFirst()
                .repositories().getFirst().storage()).contains(s3Binding());
        assertThat(dispatcher.dispatch(new pro.deta.orion.command.CommandRequest(
                "repository create acme/team/forbidden secret-key=secret", context)))
                .isInstanceOf(pro.deta.orion.command.CommandResult.Failure.class);
        assertThat(repositories.exists("acme/team/forbidden")).isFalse();
    }

    @Test
    void scopedDenyWinsAndRepositoryCreateWithoutConnectionUseHasNoSideEffects() throws Exception {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        EVENTS.await();
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
        EVENTS.await();
        int saves = storage.saves;
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())),
                StorageManagement.FailureCode.DENIED);
        EVENTS.await();
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
        EVENTS.await();
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
            EVENTS.await();
            int saves = storage.saves;
            boolean create = action == GrantKey.CREATE;
            char[] secret = "replacement".toCharArray();
            assertFailure(management.saveConnection(actor, Optional.of(organization), revision(), create,
                    new StorageManagement.S3Input(create ? "archive-new" : "archive", null, "us-east-1", true,
                            "access-id", secret, null, false)), StorageManagement.FailureCode.DENIED);
        EVENTS.await();
            assertThat(secret).containsOnly('\0');
            assertThat(storage.saves).isEqualTo(saves);
            assertThat(activeDocument().organizations().getFirst().secrets()).isEqualTo(org.secrets());
        }
    }

    @Test
    void repositoryReaderNeedsNoConnectionUseAndExistingLocalRepositoryCannotBeRebound() throws Exception {
        management.saveConnection(actor, Optional.of(organization), revision(), true, input("secret".toCharArray()));
        EVENTS.await();
        management.createRepository(actor, "acme/team/local", Optional.empty());
        EVENTS.await();
        assertFailure(management.createRepository(actor, "acme/team/local", Optional.of(s3Binding())),
                StorageManagement.FailureCode.CONFLICT);
        EVENTS.await();
        management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding()));
        EVENTS.await();
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
        EVENTS.await();
        SecurityContext admin = SecurityContext.createContext().withUserIdentity(new InternalUserImpl("operator", List.of()));
        StorageManagement.S3Input defaults = new StorageManagement.S3Input("archive", null, "us-east-1", false,
                null, null, null, true);
        assertThat(management.saveConnection(admin, Optional.empty(), revision(), true, defaults))
                .isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
        assertFailure(management.connections(actor, Optional.empty()), StorageManagement.FailureCode.DENIED);
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(new S3StorageBinding(
                new ConnectionReference(ConnectionReference.Scope.SYSTEM, "archive"), s3Binding().location()))),
                StorageManagement.FailureCode.DENIED);
        EVENTS.await();
        assertThat(management.saveConnection(admin, Optional.of(organization), revision(), true, defaults))
                .isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
        assertFailure(management.createRepository(actor, "acme/team/archive", Optional.of(s3Binding())),
                StorageManagement.FailureCode.DENIED);
        EVENTS.await();
        assertThat(management.saveConnection(actor, Optional.of(organization), revision(), false,
                input("own-explicit-secret".toCharArray()))).isInstanceOf(StorageManagement.Success.class);
        EVENTS.await();
        assertThat(((StorageManagement.Success<StorageManagement.Connections>) management.connections(
                actor, Optional.of(organization))).value().connections().getFirst().canUse()).isTrue();
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
        EVENTS.await();
        return ((OrionRevision.Stored) acl.configuration().revision()).value();
    }

    private OrionDocument activeDocument() {
        EVENTS.await();
        return acl.configuration().result().valueOrFailure("Cannot read configuration document");
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

    private static final class MemoryStorage implements OrionVersionedConfigurationStorage {
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
            EVENTS.manager().publishAndWait(new ConfigurationChanged(this, snapshot));
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
        public synchronized OrionRevision save(
                OrionRevision expected, OrionDocument document, ChangeMetadata metadata) {
            if (!snapshot.revision().equals(expected)) {
                throw new OrionConfigurationConcurrentUpdateException("Changed", null);
            }
            if (snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .equals(document)) return snapshot.revision();
            snapshot = new OrionDocumentRevision(Result.of(document), new OrionRevision.Stored("v" + ++saves));
            EVENTS.manager().publish(new ConfigurationChanged(this, snapshot));
            return snapshot.revision();
        }
    }
}
