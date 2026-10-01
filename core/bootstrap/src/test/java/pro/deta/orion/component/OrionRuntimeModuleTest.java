package pro.deta.orion.component;

import pro.deta.orion.config.OrionConfigurationEditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.RoleId;
import pro.deta.orion.schema.orion.ScopedRole;
import pro.deta.orion.schema.orion.GrantId;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.TeamId;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.config.OrionConfigurationStorage;
import pro.deta.orion.config.OrionConfigurationStorageResolver;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "acl.xml";
    private static final String TEST_PASSWORD_HASH = "acl-password-hash";

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"acme", "acme/platform", "acme/platform/api"})
    void decisionAuthorizationPreservesOrganizationBoundary(String scopePath) {
        OrionDesiredState desired = new OrionDesiredState();
        desired.publish(decisionAccessDocument(true), Optional.empty());
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, decisionAcl(desired))) {
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
        OrionDesiredState desired = new OrionDesiredState();
        desired.publish(decisionAccessDocument(true), Optional.empty());
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, decisionAcl(desired))) {
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
        OrionDesiredState desired = new OrionDesiredState();
        OrionDocument base = decisionAccessDocument(false);
        OrionDocument.Organization organization = base.organizations().getFirst();
        ScopedGrant grant = new ScopedGrant(
                new GrantId("admin"),
                ScopedGrant.Effect.ALLOW,
                List.of(new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true")));
        ScopedRole role = new ScopedRole(
                new RoleId("reviewer"), List.of(),
                List.of(GrantAddress.parse("acme/admin")));
        AccessControl.User assigned = new AccessControl.User("reviewer", null, null, null, List.of(),
                List.of("acme/reviewer"), List.of());
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, decisionAcl(desired))) {
            Decision pending = registry.register(new Decision(UUID.randomUUID(),
                    Optional.of(ConfigurationScope.parse("acme/platform/api")), "Trust", "",
                    List.of(new DecisionAction("Save", false, ignored -> Result.of(null)))))
                    .valueOrFailure("register");
            for (List<AccessControl.User> users : List.of(organization.users(), List.<AccessControl.User>of())) {
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

    private static OrionAccessControlServiceImpl decisionAcl(OrionDesiredState desired) {
        return new OrionAccessControlServiceImpl(null,
                null,
                null,
                ServerIdentityCapability.unavailable(),
                desired,
                new OrionConfigurationEditor(null, new OrionConfiguration(),
                        pro.deta.orion.keymaterial.ConfigurationCipherCapability.unavailable(),
                        pro.deta.orion.keymaterial.ConfigurationMaterialCapability.unavailable(), desired),
                java.util.Optional.empty());
    }

    @Test
    void revokedPendingDecisionCannotRunAndDeletedUserCannotAdminister() {
        OrionDesiredState desired = new OrionDesiredState();
        desired.publish(decisionAccessDocument(true), Optional.empty());
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry registry = OrionRuntimeModule.decisionRegistry(executor, decisionAcl(desired))) {
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
        OrionDesiredState desired = new OrionDesiredState();
        OrionDocument base = decisionAccessDocument(false);
        desired.publish(base.replaceAccessControl(ACLUtil.generateDefaultAccessControl("hash")), Optional.empty());
        OrionAccessControlServiceImpl acl = decisionAcl(desired);
        PrincipalAddress root = PrincipalAddress.parse("system/root");
        assertThat(acl.canAdminister(root, Optional.empty())).isTrue();
        assertThat(acl.canAdminister(root, Optional.of(ConfigurationScope.parse("acme/platform")))).isTrue();
        assertThat(acl.canAdminister(PrincipalAddress.parse("acme/root"),
                Optional.of(ConfigurationScope.parse("acme")))).isFalse();
        assertThat(acl.canAdminister(PrincipalAddress.parse("system/reviewer"), Optional.empty())).isFalse();
        AccessControlDraft locked = ACLUtil.generateDefaultAccessControl("hash").toDraft();
        locked.getUsers().getFirst().getCredentials().getFirst().setKeyId("root-auth-locked:test");
        desired.publish(base.replaceAccessControl(locked.toAccessControl()), Optional.empty());
        assertThat(acl.canAdminister(root, Optional.empty())).isFalse();
        desired.publish(base, Optional.empty());
        assertThat(acl.canAdminister(root, Optional.empty())).isFalse();
    }

    @Test
    void repositoryQualifiedAdminGrantDoesNotAuthorizeOtherScopes() {
        OrionDesiredState desired = new OrionDesiredState();
        OrionDocument base = decisionAccessDocument(false);
        OrionDocument.Organization organization = base.organizations().getFirst();
        AccessControl.User restricted = new AccessControl.User("reviewer", null, null, null, List.of(), List.of(),
                List.of(new AccessControl.Grant("admin", List.of(
                        new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true"),
                        new AccessControl.GrantExpression(AccessControl.GrantKey.REPOSITORY, "acme/platform/api")))));
        desired.publish(new OrionDocument(base.system(), List.of(new OrionDocument.Organization(
                organization.id(), "", List.of(restricted), organization.grants(), organization.roles(),
                organization.teams(), List.of(), List.of(), List.of(), organization.connections()))), Optional.empty());
        OrionAccessControlServiceImpl acl = decisionAcl(desired);
        PrincipalAddress actor = PrincipalAddress.parse("acme/reviewer");
        assertThat(acl.canAdminister(actor, Optional.of(ConfigurationScope.parse("acme/platform/api")))).isTrue();
        assertThat(acl.canAdminister(actor, Optional.of(ConfigurationScope.parse("acme/platform")))).isFalse();
        assertThat(acl.canAdminister(actor, Optional.of(ConfigurationScope.parse("acme")))).isFalse();
        assertThat(acl.canAdminister(actor, Optional.empty())).isFalse();
    }

    private static OrionDocument decisionAccessDocument(boolean allowed) {
        List<AccessControl.Grant> grants = allowed ? List.of(new AccessControl.Grant("admin",
                List.of(new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true")))) : List.of();
        AccessControl.User user = new AccessControl.User("reviewer", null, null, null,
                List.of(), List.of(), grants);
        OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("api"), "API",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of(), java.util.Optional.empty());
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
                .defaultConfigurationProvider()
                .build();

        assertThat(component.runtimeStateMachine().childStatuses().keySet()).containsExactly(
                "executor",
                "event-manager",
                "access-control",
                "agent-session-server",
                "transports");
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
        OrionConfiguration configuration = configurationWithAcl(aclDirectory.toUri().toString());

        OrionConfigurationStorage storage = runtimeOrionConfigurationStorage(configuration);

        assertInstanceOf(NativeGitOrionConfigurationStorage.class, storage);
        assertStorageLoadsUser(storage, "file-user");
    }

    @Test
    void localAclSavesToExternalRepository() {
        OrionConfiguration configuration = configurationWithAcl(tempDir.resolve("local-acl").toString());
        OrionConfigurationStorage storage = runtimeOrionConfigurationStorage(configuration);

        storage.save(
                new ConfigurationFile("native acl".getBytes(StandardCharsets.UTF_8), Optional.empty()),
                "native acl", new UserEmail("tester", "tester@example.test"));

        ConfigurationFile snapshot =
                storage.load().valueOrFailure("ACL should load from local storage");
        assertEquals("native acl", new String(snapshot.content(), StandardCharsets.UTF_8));
    }

    @Test
    void localLocatorUsesConfiguredNativeRepository() {
        OrionConfiguration configuration = configurationWithAcl("local:internal/settings");
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();

        OrionConfigurationStorage storage = resolvedStorage(configuration, provider);
        storage.save(
                new ConfigurationFile("versioned acl".getBytes(StandardCharsets.UTF_8), Optional.empty()),
                "versioned acl", UserEmail.EMPTY);

        assertInstanceOf(NativeGitOrionConfigurationStorage.class, storage);
        assertEquals(List.of("internal/settings"), provider.repositoryNames());
        assertEquals(
                "versioned acl",
                new String(storage.load().valueOrFailure("ACL should load").content(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void remoteGitAclIsUnsupported() {
        OrionConfiguration configuration = configurationWithAcl("ssh://git@example.test/acl.git");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOrionConfigurationStorage(configuration));

        assertEquals("Orion configuration requires a resolved Git repository", error.getMessage());
    }

    private OrionConfigurationStorage runtimeOrionConfigurationStorage(OrionConfiguration configuration) {
        return resolvedStorage(configuration, new InMemoryNativeGitRepositoryProvider());
    }

    private static OrionConfigurationStorage resolvedStorage(
            OrionConfiguration configuration,
            InMemoryNativeGitRepositoryProvider backend) {
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
        ResolvedBootstrapSource resolved = provider.resolveProvisional(
                BootstrapRepositorySources.CONFIGURATION,
                configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        return new OrionConfigurationStorageResolver(
                new BootstrapRepositorySources(List.of(resolved)),
                provider).resolve();
    }

    private byte[] aclBytes(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(accessControlWithUser(userId)), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.ARGON2, TEST_PASSWORD_HASH));
        return draft.toAccessControl();
    }

    private void assertStorageLoadsUser(OrionConfigurationStorage storage, String userId) throws Exception {
        ConfigurationFile snapshot = storage.load().valueOrFailure("ACL should load from storage");
        AccessControl accessControl =
                OrionXml.read(new ByteArrayInputStream(snapshot.content()))
                        .system().accessControl();
        assertEquals(1, accessControl.getUsers().size());
        assertEquals(userId, accessControl.getUsers().getFirst().getId());
    }

    private OrionConfiguration configurationWithAcl(String location) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getAccessControl().setRef(BRANCH);
        configuration.getBootstrap().getAccessControl().setPath(ACL_FILE);
        return configuration;
    }
}
