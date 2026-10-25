package pro.deta.orion.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.GrantAddress;
import pro.deta.orion.schema.orion.v2.RoleId;
import pro.deta.orion.schema.orion.v2.ScopedRole;
import pro.deta.orion.schema.orion.v2.GrantId;
import pro.deta.orion.schema.orion.v2.ScopedGrant;
import pro.deta.orion.schema.orion.v2.TeamId;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.auth.DefaultAccessControl;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;
import pro.deta.orion.schema.orion.OrionXml;

import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.test.integration.TestConfiguration;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrionRuntimeModuleTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final String BRANCH = "master";
    private static final String ACL_FILE = "acl.xml";
    private static final String TEST_PASSWORD_HASH = "acl-password-hash";

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"acme", "acme/platform", "acme/platform/api"})
    void decisionAuthorizationPreservesOrganizationBoundary(String scopePath) {
        TestConfiguration desired = new TestConfiguration(testRuntime);
        desired.publish(decisionAccessDocument(true), Optional.empty());
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, desired.acl())) {
            Decision pending = registry.register(new Decision(UUID.randomUUID(),
                Optional.ofNullable(scopePath).map(ConfigurationScope::parse), "Confirm operation", "",
                List.of(new DecisionAction("Replace", false, actor -> Result.of(null)),
                        new DecisionAction("Reject", false, actor -> Result.of(null)))))
                    .valueOrFailure("register pending decision");
            PrincipalAddress foreign = PrincipalAddress.parse("other/reviewer");
            assertThat(registry.list(foreign)).isEmpty();
            assertThat(registry.find(pending.request().id(), foreign)).isEmpty();
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer(0, foreign)).isFailure())
                    .isTrue();
            assertThat(pending.result().toCompletableFuture()).isNotDone();
            PrincipalAddress actor = PrincipalAddress.parse(
                    scopePath == null ? "system/reviewer" : "acme/reviewer");
            DecisionAnswer decision = new DecisionAnswer(0, actor);

            assertThat(registry.list(actor)).containsExactly(pending.request());
            assertThat(registry.find(pending.request().id(), actor)).contains(pending.request());
            assertThat(registry.decide(pending.request().id(), decision)).isEqualTo(Result.of(decision));
            assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().join())
                .isEqualTo(decision);
            assertThat(registry.list(actor)).isEmpty();
        }
    }

    @Test
    void decisionAccessRevocationAppliesToReadsAnswersRetriesAndDismissal() {
        TestConfiguration desired = new TestConfiguration(testRuntime);
        desired.publish(decisionAccessDocument(true), Optional.empty());
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, desired.acl())) {
            Decision pending = registry.register(new Decision(UUID.randomUUID(),
                    Optional.of(ConfigurationScope.parse("acme/platform/api")), "Trust", "",
                    List.of(new DecisionAction("Save", true,
                            ignored -> new Result.Failure<>(Result.FailureCode.CREATION_FAILED, "save failed")))))
                    .valueOrFailure("register");
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer(0, actor)).isFailure()).isFalse();
            assertThat(pending.result().toCompletableFuture().join().isFailure()).isTrue();
            desired.publish(decisionAccessDocument(false), Optional.empty());
            assertThat(registry.list(actor)).isEmpty();
            assertThat(registry.find(pending.request().id(), actor)).isEmpty();
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer(0, actor)).isFailure()).isTrue();
            assertThat(registry.retry(pending.request().id(), actor).isFailure()).isTrue();
            assertThat(registry.dismiss(pending.request().id(), actor).isFailure()).isTrue();
            assertThat(pending.request().state()).isEqualTo(
                    pro.deta.orion.decision.DecisionRequest.State.FAILED);
            desired.publish(decisionAccessDocument(true), Optional.empty());
            assertThat(registry.find(pending.request().id(), actor)).isPresent();
            assertThat(registry.dismiss(pending.request().id(), actor).isFailure()).isFalse();
        }
    }

    @Test
    void pendingDecisionRechecksRoleRevocationAndUserRemoval() {
        TestConfiguration desired = new TestConfiguration(testRuntime);
        OrionDocument base = decisionAccessDocument(false);
        OrionDocument.Organization organization = base.organizations().getFirst();
        ScopedGrant grant = new ScopedGrant(
                new GrantId("admin"),
                ScopedGrant.Effect.ALLOW,
                List.of(new GrantExpression(GrantKey.ADMIN, "true")));
        ScopedRole role = new ScopedRole(
                new RoleId("reviewer"), List.of(),
                List.of(GrantAddress.parse("acme/admin")));
        User assigned = new User("reviewer", null, null, null, List.of(),
                List.of("acme/reviewer"), List.of());
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, desired.acl())) {
            Decision pending = registry.register(new Decision(UUID.randomUUID(),
                    Optional.of(ConfigurationScope.parse("acme/platform/api")), "Trust", "",
                    List.of(new DecisionAction("Save", false, ignored -> Result.of(null)))))
                    .valueOrFailure("register");
            for (List<User> users : List.of(organization.users(), List.<User>of())) {
                desired.publish(new OrionDocument(base.system(), List.of(new OrionDocument.Organization(
                        organization.id(), "", List.of(assigned), List.of(grant), List.of(role),
                        organization.teams(), List.of(), List.of(), List.of(), organization.connections()))), Optional.empty());
                assertThat(registry.list(actor)).containsExactly(pending.request());
                desired.publish(new OrionDocument(base.system(), List.of(new OrionDocument.Organization(
                        organization.id(), "", users, List.of(grant), List.of(role),
                        organization.teams(), List.of(), List.of(), List.of(), organization.connections()))), Optional.empty());
                assertThat(registry.list(actor)).isEmpty();
                assertThat(registry.find(pending.request().id(), actor)).isEmpty();
                assertThat(registry.decide(pending.request().id(), new DecisionAnswer(0, actor)).isFailure())
                        .isTrue();
                assertThat(pending.result().toCompletableFuture()).isNotDone();
            }
        }
    }

    @Test
    void revokedPendingDecisionCannotRunAndDeletedUserCannotAdminister() {
        TestConfiguration desired = new TestConfiguration(testRuntime);
        desired.publish(decisionAccessDocument(true), Optional.empty());
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, desired.acl())) {
            java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
            Decision pending = registry.register(new Decision(UUID.randomUUID(),
                    Optional.of(ConfigurationScope.parse("acme/platform")), "Trust", "",
                    List.of(new DecisionAction("Save", true, ignored -> {
                        executions.incrementAndGet();
                        return Result.of(null);
                    })))).valueOrFailure("register");
            assertThat(registry.find(pending.request().id(), actor)).isPresent();
            desired.publish(decisionAccessDocument(false), Optional.empty());
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer(0, actor)).isFailure()).isTrue();
            assertThat(executions).hasValue(0);
            assertThat(pending.result().toCompletableFuture()).isNotDone();
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.empty());
            assertThat(registry.find(pending.request().id(), actor)).isEmpty();
        }
    }

    @Test
    void systemAdministrationUsesCurrentRolesAndPreservesPrincipalIsolation() {
        TestConfiguration desired = new TestConfiguration(testRuntime);
        OrionDocument base = decisionAccessDocument(false);
        desired.publish(base.replaceAccessControl(DefaultAccessControl.create("hash")), Optional.empty());
        OrionAccessControlServiceImpl acl = desired.acl();
        PrincipalAddress root = PrincipalAddress.parse("system/root");
        assertThat(acl.canAdminister(root, Optional.empty())).isTrue();
        assertThat(acl.canAdminister(root, Optional.of(ConfigurationScope.parse("acme/platform")))).isTrue();
        assertThat(acl.canAdminister(PrincipalAddress.parse("acme/root"),
                Optional.of(ConfigurationScope.parse("acme")))).isFalse();
        assertThat(acl.canAdminister(PrincipalAddress.parse("system/reviewer"), Optional.empty())).isFalse();
        AccessControl initial = DefaultAccessControl.create("hash");
        User originalRoot = initial.users().getFirst();
        Credential password = originalRoot.credentials().getFirst();
        User lockedRoot = new User(originalRoot.id(), originalRoot.first(),
                originalRoot.last(), originalRoot.email(), List.of(new Credential(
                        password.type(), "root-auth-locked:test", password.value())),
                originalRoot.roles(), originalRoot.grants());
        AccessControl locked = new AccessControl(List.of(lockedRoot), initial.roles(), initial.grants());
        desired.publish(base.replaceAccessControl(locked), Optional.empty());
        assertThat(acl.canAdminister(root, Optional.empty())).isFalse();
        desired.publish(base, Optional.empty());
        assertThat(acl.canAdminister(root, Optional.empty())).isFalse();
    }

    @Test
    void repositoryQualifiedAdminGrantDoesNotAuthorizeOtherScopes() {
        TestConfiguration desired = new TestConfiguration(testRuntime);
        OrionDocument base = decisionAccessDocument(false);
        OrionDocument.Organization organization = base.organizations().getFirst();
        User restricted = new User("reviewer", null, null, null, List.of(), List.of(),
                List.of(new Grant("admin", List.of(
                        new GrantExpression(GrantKey.ADMIN, "true"),
                        new GrantExpression(GrantKey.REPOSITORY, "acme/platform/api")))));
        desired.publish(new OrionDocument(base.system(), List.of(new OrionDocument.Organization(
                organization.id(), "", List.of(restricted), organization.grants(), organization.roles(),
                organization.teams(), List.of(), List.of(), List.of(), organization.connections()))), Optional.empty());
        OrionAccessControlServiceImpl acl = desired.acl();
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        assertThat(acl.canAdminister(actor, Optional.of(ConfigurationScope.parse("acme/platform/api")))).isTrue();
        assertThat(acl.canAdminister(actor, Optional.of(ConfigurationScope.parse("acme/platform")))).isFalse();
        assertThat(acl.canAdminister(actor, Optional.of(ConfigurationScope.parse("acme")))).isFalse();
        assertThat(acl.canAdminister(actor, Optional.empty())).isFalse();
    }

    private static OrionDocument decisionAccessDocument(boolean allowed) {
        List<Grant> grants = allowed ? List.of(new Grant("admin",
                List.of(new GrantExpression(GrantKey.ADMIN, "true")))) : List.of();
        User user = new User("reviewer", null, null, null,
                List.of(), List.of(), grants);
        OrionDocument.Repository repository = new OrionDocument.Repository("api", "API",
                OrionDocument.Repository.DEFAULT_BRANCH,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.empty());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("platform"), "Platform",
                List.of(), List.of(), List.of(repository));
        return new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(List.of(user), List.of(), List.of())),
                List.of(new OrionDocument.Organization(new OrganizationId("acme"), "Acme", List.of(user),
                        List.of(), List.of(), List.of(team), List.of(), List.of(), List.of(), List.of())));
    }

    @Test
    void runtimeOwnsAgentServerBeforeExternallyVisibleTransports() {
        OrionComponent component = DaggerOrionComponent.builder()
                .defaultBootstrapConfiguration()
                .build();

        assertThat(component.runtimeStateMachine().childStatuses().keySet()).containsExactly(
                "executor",
                "event-manager",
                "configuration",
                "access-control",
                "agent-session-server",
                "transports",
                "acme");
    }

    @Test
    void fileAclStartsFromExternalRepository() throws Exception {
        Path aclDirectory = tempDir.resolve("acl-directory");
        Path seed = tempDir.resolve("acl-seed");
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init()
                .setDirectory(seed.toFile()).setInitialBranch("main").call()) {
            Files.write(seed.resolve(ACL_FILE), aclBytes("file-user"));
            git.add().addFilepattern(ACL_FILE).call();
            git.commit().setMessage("initial ACL").setAuthor("Test", "test@example.test").call();
            git.branchCreate().setName(BRANCH.replace("refs/heads/", "")).call();
            try (org.eclipse.jgit.api.Git bare = org.eclipse.jgit.api.Git.cloneRepository()
                    .setURI(seed.toUri().toString()).setDirectory(aclDirectory.toFile()).setBare(true).call()) {
                assertThat(bare.getRepository().isBare()).isTrue();
            }
        }
        BootstrapConfiguration configuration = configurationWithAcl(aclDirectory.toUri().toString());

        OrionVersionedConfigurationStorage storage = runtimeOrionVersionedConfigurationStorage(configuration);

        assertInstanceOf(NativeGitOrionConfigurationStorage.class, storage);
        assertStorageLoadsUser(storage, "file-user");
    }

    @Test
    void localAclSavesToExternalRepository() {
        BootstrapConfiguration configuration = configurationWithAcl(tempDir.resolve("local-acl").toString());
        OrionVersionedConfigurationStorage storage = runtimeOrionVersionedConfigurationStorage(configuration);

        storage.save(
                new OrionRevision.Absent(),
                OrionDocument.withAccessControl(accessControlWithUser("native-user")),
                new ChangeMetadata(new UserEmail("tester", "tester@example.test"), "native acl"));

        OrionDocumentRevision snapshot = storage.load();
        assertEquals("native-user", snapshot.result()
                .valueOrFailure("Cannot read configuration document")
                .system().accessControl().users().getFirst().id());
    }

    @Test
    void localLocatorUsesConfiguredNativeRepository() {
        BootstrapConfiguration configuration = configurationWithAcl("local:internal/settings");
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));

        OrionVersionedConfigurationStorage storage = resolvedStorage(configuration, provider);
        storage.save(
                new OrionRevision.Absent(),
                OrionDocument.withAccessControl(accessControlWithUser("versioned-user")),
                new ChangeMetadata(UserEmail.EMPTY, "versioned acl"));

        assertInstanceOf(NativeGitOrionConfigurationStorage.class, storage);
        assertEquals(List.of("internal/settings"), provider.provider().repositoryNames());
        assertEquals(
                "versioned-user",
                storage.load()
                        .result().valueOrFailure("Cannot read configuration document")
                        .system()
                        .accessControl()
                        .users()
                        .getFirst()
                        .id());
    }

    @Test
    void remoteGitAclIsUnsupported() {
        BootstrapConfiguration configuration = configurationWithAcl("ssh://git@example.test/acl.git");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOrionVersionedConfigurationStorage(configuration));

        assertEquals("Orion configuration requires a resolved Git repository", error.getMessage());
    }

    private OrionVersionedConfigurationStorage runtimeOrionVersionedConfigurationStorage(
            BootstrapConfiguration configuration) {
        return resolvedStorage(configuration, new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events())));
    }

    private static OrionVersionedConfigurationStorage resolvedStorage(
            BootstrapConfiguration configuration,
            NativeGitRepositoryFactory provider) {
        provider.resolveProvisional(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE,
                configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        return new NativeGitOrionConfigurationStorage(provider, configuration.getBootstrap().getAccessControl());
    }

    private byte[] aclBytes(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(accessControlWithUser(userId)), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        User user = new User(userId, null, null, userId + "@example.test",
                List.of(new Credential(CredentialType.ARGON2, TEST_PASSWORD_HASH)),
                List.of(), List.of());
        return new AccessControl(List.of(user), List.of(), List.of());
    }

    private void assertStorageLoadsUser(OrionVersionedConfigurationStorage storage, String userId)
            throws Exception {
        OrionDocumentRevision snapshot = storage.load();
        AccessControl accessControl =
                snapshot.result().valueOrFailure("Cannot read configuration document").system().accessControl();
        assertEquals(1, accessControl.users().size());
        assertEquals(userId, accessControl.users().getFirst().id());
    }

    private BootstrapConfiguration configurationWithAcl(String location) {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getAccessControl().setRef(BRANCH);
        configuration.getBootstrap().getAccessControl().setPath(ACL_FILE);
        return configuration;
    }
}
