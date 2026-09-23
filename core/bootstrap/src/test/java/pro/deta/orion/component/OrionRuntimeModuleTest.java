package pro.deta.orion.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.TeamId;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.acl.storage.NativeGitAccessControlStorage;
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
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

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

    private final XmlService xmlService = new XmlService();

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

    private static OrionAccessControlServiceImpl decisionAcl(OrionDesiredState desired) {
        return new OrionAccessControlServiceImpl(null, null, null, null,
                ServerIdentityCapability.unavailable(), desired);
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
                organization.teams(), List.of(), List.of(), List.of()))), Optional.empty());
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
                List.of(), List.of(), List.of(), List.of());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("platform"), "Platform",
                List.of(), List.of(), List.of(repository));
        return new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(List.of(user), List.of(), List.of())),
                List.of(new OrionDocument.Organization(new OrganizationId("acme"), "Acme", List.of(user),
                        List.of(), List.of(), List.of(team), List.of(), List.of(), List.of())));
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
    void runtimeComponentExposesNoRawMaterialOwnerOrService() {
        assertThat(OrionComponent.class.getMethods())
                .noneMatch(method -> method.getReturnType().equals(OrionKeyMaterial.class))
                .noneMatch(method -> method.getReturnType().equals(KeyMaterialService.class));
        assertThat(OrionComponent.Builder.class.getMethods())
                .noneMatch(method -> List.of(method.getParameterTypes()).contains(OrionKeyMaterial.class))
                .noneMatch(method -> List.of(method.getParameterTypes()).contains(KeyMaterialService.class));
    }

    @Test
    void fileAclStartsFromLocalDirectory() throws Exception {
        Path aclDirectory = tempDir.resolve("acl-directory");
        Files.createDirectories(aclDirectory);
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytes("file-user"));
        OrionConfiguration configuration = configurationWithAcl(aclDirectory.toUri().toString());

        AccessControlStorage storage = runtimeAccessControlStorage(configuration);

        assertInstanceOf(LocalAccessControlStorage.class, storage);
        assertStorageLoadsUser(storage, "file-user");
    }

    @Test
    void localAclSavesToLocalDirectory() {
        OrionConfiguration configuration = configurationWithAcl(tempDir.resolve("local-acl").toString());
        AccessControlStorage storage = runtimeAccessControlStorage(configuration);

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, "native acl".getBytes(StandardCharsets.UTF_8)),
                new AccessControlSaveRequest("native acl", new UserEmail("tester", "tester@example.test")));

        AccessControlSnapshot snapshot =
                storage.load().valueOrFailure("ACL should load from local storage");
        assertEquals("native acl", new String(snapshot.files().get(ACL_FILE), StandardCharsets.UTF_8));
    }

    @Test
    void localLocatorUsesConfiguredNativeRepository() {
        OrionConfiguration configuration = configurationWithAcl("local:internal/settings");
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();

        AccessControlStorage storage = resolvedStorage(configuration, provider);
        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, "versioned acl".getBytes(StandardCharsets.UTF_8)),
                new AccessControlSaveRequest("versioned acl", UserEmail.EMPTY));

        assertInstanceOf(NativeGitAccessControlStorage.class, storage);
        assertEquals(List.of("internal/settings"), provider.repositoryNames());
        assertEquals(
                "versioned acl",
                new String(storage.load().valueOrFailure("ACL should load").files().get(ACL_FILE),
                        StandardCharsets.UTF_8));
    }

    @Test
    void remoteGitAclIsUnsupported() {
        OrionConfiguration configuration = configurationWithAcl("ssh://git@example.test/acl.git");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeAccessControlStorage(configuration));

        assertEquals("Unsupported ACL location: ssh://git@example.test/acl.git", error.getMessage());
    }

    private AccessControlStorage runtimeAccessControlStorage(OrionConfiguration configuration) {
        return resolvedStorage(configuration, new InMemoryNativeGitRepositoryProvider());
    }

    private static AccessControlStorage resolvedStorage(
            OrionConfiguration configuration,
            InMemoryNativeGitRepositoryProvider backend) {
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
        ResolvedBootstrapSource resolved = provider.resolveProvisional(
                BootstrapRepositorySources.CONFIGURATION,
                configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        return new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of(resolved)),
                provider).resolve();
    }

    private byte[] aclBytes(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControlWithUser(userId), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.ARGON2, TEST_PASSWORD_HASH));
        return draft.toAccessControl();
    }

    private void assertStorageLoadsUser(AccessControlStorage storage, String userId) throws Exception {
        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load from storage");
        AccessControl accessControl =
                xmlService.deserialize(new ByteArrayInputStream(snapshot.files().get(ACL_FILE)));
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
