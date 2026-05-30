package pro.deta.orion.component;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.acl.storage.VersionedAccessControlStorage;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.FileGitRepositoryProvider;
import pro.deta.orion.git.s3.S3GitRepositoryProvider;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionRuntimeModuleTest {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "acl.xml";
    private static final String TEST_PASSWORD_HASH = "acl-password-hash";

    @TempDir
    private Path tempDir;

    private final XmlService xmlService = new XmlService();

    @Test
    void runtimeInitPlanOmitsDisabledTransportInit() {
        OrionComponent component = runtimeComponent(defaultRuntimeConfiguration());

        String plan = component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.INIT);

        assertTrue(plan.contains("JGIT_RUNTIME"));
        assertTrue(plan.contains("EVENT_MANAGER after JGIT_RUNTIME"));
        assertFalse(plan.contains("SSH_TRANSPORT_INIT"));
        assertFalse(plan.contains("ACL_INIT"));
    }

    @Test
    void runtimeInitPlanIncludesEnabledSshTransportInit() {
        OrionComponent component = runtimeComponent(runtimeConfigurationWithEnabledTransports());

        String plan = component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.INIT);

        assertTrue(plan.contains("JGIT_RUNTIME"));
        assertTrue(plan.contains("SSH_TRANSPORT_INIT"));
        assertTrue(plan.contains("EVENT_MANAGER after JGIT_RUNTIME"));
    }

    @Test
    void runtimeStartingPlanOmitsDisabledTransportServices() {
        OrionComponent component = runtimeComponent(defaultRuntimeConfiguration());

        String plan = component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.STARTING);

        assertTrue(plan.contains("ACL_LOAD"));
        assertTrue(plan.contains("TRANSPORTS_START after ACL_LOAD"));
        assertFalse(plan.contains("HTTP_TRANSPORT_START"));
        assertFalse(plan.contains("GIT_TRANSPORT_START"));
        assertFalse(plan.contains("SSH_TRANSPORT_START"));
    }

    @Test
    void runtimeStartingPlanOrdersEnabledTransportsAfterAcl() {
        OrionComponent component = runtimeComponent(runtimeConfigurationWithEnabledTransports());

        String plan = component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.STARTING);

        assertTrue(plan.contains("ACL_LOAD"));
        assertTrue(plan.contains("TRANSPORTS_START after ACL_LOAD"));
        assertTrue(plan.contains("HTTP_TRANSPORT_START after TRANSPORTS_START"));
        assertTrue(plan.contains("GIT_TRANSPORT_START after TRANSPORTS_START"));
        assertTrue(plan.contains("SSH_TRANSPORT_START after TRANSPORTS_START"));
    }

    @Test
    void runtimeStoppingPlanOmitsDisabledTransportServices() {
        OrionComponent component = runtimeComponent(defaultRuntimeConfiguration());

        String plan = component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.STOPPING);

        assertTrue(plan.contains("TRANSPORTS_STOP"));
        assertFalse(plan.contains("HTTP_TRANSPORT_STOP"));
        assertFalse(plan.contains("GIT_TRANSPORT_STOP"));
        assertFalse(plan.contains("SSH_TRANSPORT_STOP"));
        assertTrue(plan.contains("JGIT_RUNTIME_STOP after TRANSPORTS_STOP"));
        assertTrue(plan.contains("EVENT_MANAGER_STOP after TRANSPORTS_STOP"));
        assertTrue(plan.contains("EXECUTOR_STOP after EVENT_MANAGER_STOP"));
    }

    @Test
    void runtimeStoppingPlanOrdersEnabledTransportsBeforeEventsAndExecutor() {
        OrionComponent component = runtimeComponent(runtimeConfigurationWithEnabledTransports());

        String plan = component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.STOPPING);

        assertTrue(plan.contains("HTTP_TRANSPORT_STOP"));
        assertTrue(plan.contains("GIT_TRANSPORT_STOP"));
        assertTrue(plan.contains("SSH_TRANSPORT_STOP"));
        assertTrue(plan.contains("TRANSPORTS_STOP after HTTP_TRANSPORT_STOP, GIT_TRANSPORT_STOP, SSH_TRANSPORT_STOP"));
        assertTrue(plan.contains("JGIT_RUNTIME_STOP after TRANSPORTS_STOP"));
        assertTrue(plan.contains("EVENT_MANAGER_STOP after TRANSPORTS_STOP"));
        assertTrue(plan.contains("EXECUTOR_STOP after EVENT_MANAGER_STOP"));
    }

    @Test
    void runtimeServiceMapShowsDisabledTransportBarrierOnly() {
        OrionComponent component = runtimeComponent(defaultRuntimeConfiguration());

        String serviceMap = component.orionApplicationLifecycle().describeServiceMap();

        assertTrue(serviceMap.contains("TransportLifecycleBarrier: TRANSPORTS_START after ACL_LOAD"));
        assertTrue(serviceMap.contains("TransportLifecycleBarrier: TRANSPORTS_STOP"));
        assertTrue(serviceMap.contains("OrionJGitRuntime: JGIT_RUNTIME_STOP after TRANSPORTS_STOP"));
        assertFalse(serviceMap.contains("JettyHTTPServer: HTTP_TRANSPORT_START"));
        assertFalse(serviceMap.contains("TransportLifecycleStateMachine: GIT_TRANSPORT_START"));
        assertFalse(serviceMap.contains("GitNativeTransportService: GIT_TRANSPORT_START"));
        assertFalse(serviceMap.contains("GitNativeTransportStateMachine: GIT_TRANSPORT_START"));
        assertFalse(serviceMap.contains("GitSshTransportService: SSH_TRANSPORT_START"));
    }

    @Test
    void runtimeServiceMapShowsEnabledTransportBarrierDependencies() {
        OrionComponent component = runtimeComponent(runtimeConfigurationWithEnabledTransports());

        String serviceMap = component.orionApplicationLifecycle().describeServiceMap();

        assertTrue(serviceMap.contains("TransportLifecycleBarrier: TRANSPORTS_START after ACL_LOAD"));
        assertTrue(serviceMap.contains(
                "TransportLifecycleBarrier: TRANSPORTS_STOP after HTTP_TRANSPORT_STOP, GIT_TRANSPORT_STOP, SSH_TRANSPORT_STOP"));
        assertTrue(serviceMap.contains("TransportLifecycleStateMachine: GIT_TRANSPORT_START after TRANSPORTS_START"));
        assertTrue(serviceMap.contains("TransportLifecycleStateMachine: GIT_TRANSPORT_STOP"));
        assertFalse(serviceMap.contains("GitNativeTransportService: GIT_TRANSPORT_START"));
        assertFalse(serviceMap.contains("GitNativeTransportService: GIT_TRANSPORT_STOP"));
        assertFalse(serviceMap.contains("GitNativeTransportStateMachine: GIT_TRANSPORT_START"));
        assertFalse(serviceMap.contains("GitNativeTransportStateMachine: GIT_TRANSPORT_STOP"));
    }

    @Test
    void runtimeModuleRegistersTransportAggregateWithoutStateMachineSet() {
        List<String> parameterTypes = new ArrayList<>();
        for (Method method : OrionRuntimeModule.class.getDeclaredMethods()) {
            for (Type parameterType : method.getGenericParameterTypes()) {
                parameterTypes.add(parameterType.getTypeName());
            }
        }

        assertTrue(containsType(parameterTypes, TransportLifecycleStateMachine.class.getName()));
        assertFalse(containsType(parameterTypes, "pro.deta.orion.lifecycle.OrionLifecycleStateMachine"));
        assertFalse(containsType(parameterTypes, "GitNativeTransportStateMachine"));
    }

    @Test
    void runtimeModuleProvidesGitTransportConfigFromRuntimeConfiguration() {
        OrionConfiguration configuration = defaultRuntimeConfiguration();
        configuration.getTransport().getGit().setEnabled(true);
        configuration.getTransport().getGit().setPort(19418);

        GitTransportConfig gitTransportConfig = OrionRuntimeModule.gitTransportConfig(configuration);

        assertTrue(gitTransportConfig.isEnabled());
        assertEquals(19418, gitTransportConfig.getPort());
    }

    @Test
    void runtimePlanDoesNotLoadServerKeysWhenTransportsAreOnlyRegistered() {
        OrionComponent component = runtimeComponent(runtimeConfigurationWithEnabledTransports());

        component.orionApplicationLifecycle().describeLifecycle();

        assertFalse(tempDir.resolve("server-identity").toFile().exists());
        assertFalse(tempDir.resolve("ssh-host-keys").toFile().exists());
    }

    @Test
    void runtimeLifecycleResolvesWithHttpShutdownRoute() {
        OrionConfiguration configuration = defaultRuntimeConfiguration();
        configuration.getTransport().getHttp().setEnabled(true);
        OrionComponent component = runtimeComponent(configuration);

        assertDoesNotThrow(() -> component.orionApplicationLifecycle().describeTaskPlan(ApplicationState.STARTING));
    }

    @Test
    void runtimeRepositoryProviderUsesFileStorageLocation() {
        OrionComponent component = runtimeComponent(defaultRuntimeConfiguration());

        assertInstanceOf(FileGitRepositoryProvider.class, component.gitRepositoryProvider());
    }

    @Test
    void runtimeRepositoryProviderUsesS3StorageLocation() {
        OrionConfiguration configuration = defaultRuntimeConfiguration();
        configuration.getStorage().setLocation("s3://orion/repositories");
        OrionComponent component = runtimeComponent(configuration);

        assertInstanceOf(S3GitRepositoryProvider.class, component.gitRepositoryProvider());
    }

    @Test
    void runtimeRepositoryProviderRejectsUnsupportedStorageLocation() {
        OrionConfiguration configuration = defaultRuntimeConfiguration();
        configuration.getStorage().setLocation("ssh://git@example.test/repositories");
        OrionComponent component = runtimeComponent(configuration);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                component::gitRepositoryProvider);

        assertEquals("Unsupported repository storage location: ssh://git@example.test/repositories", error.getMessage());
    }

    @Test
    void localGitAreaAclUsesVersionedStorageWithoutGitAccessArea() {
        OrionConfiguration configuration = configurationWithGitAcl("local:team/project");
        FileGitRepositoryProvider repositoryProvider = new FileGitRepositoryProvider(new ConfigurationContext(configuration));

        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        assertEquals(VersionedAccessControlStorage.class, storage.getClass());
        assertEquals(ACL_FILE, storage.primaryPath());
    }

    @Test
    void fileAclStartsFromLocalDirectory() throws Exception {
        Path aclDirectory = tempDir.resolve("acl-directory");
        Files.createDirectories(aclDirectory);
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytes("file-user"));
        OrionConfiguration configuration = configurationWithGitAcl(aclDirectory.toUri().toString());
        FileGitRepositoryProvider repositoryProvider = new FileGitRepositoryProvider(new ConfigurationContext(configuration));
        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        assertInstanceOf(LocalAccessControlStorage.class, storage);
        assertStorageLoadsUser(storage, "file-user");
    }

    @Test
    void gitAreaAclStartsFromVersionedStorage() throws Exception {
        OrionConfiguration configuration = configurationWithGitAcl("local:team/project");
        RecordingGitRepositoryProvider repositoryProvider = new RecordingGitRepositoryProvider(configuration);
        seedBareRepository(repositoryProvider.repositoryPath("team/project"), "area-user");
        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        assertEquals(VersionedAccessControlStorage.class, storage.getClass());
        assertStorageLoadsUser(storage, "area-user");
        assertEquals(1, repositoryProvider.findCalls);
        assertEquals(0, repositoryProvider.findOrCreateCalls);
    }

    @Test
    void localGitAreaAclCreatesMissingRepositoryWhenInitialConfigurationIsSaved() {
        OrionConfiguration configuration = configurationWithGitAcl("local:orion");
        RecordingGitRepositoryProvider repositoryProvider = new RecordingGitRepositoryProvider(configuration);
        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        assertEquals(VersionedAccessControlStorage.class, storage.getClass());
        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, "initial acl".getBytes(StandardCharsets.UTF_8)),
                new AccessControlSaveRequest("initial acl", new UserEmail("tester", "tester@example.test")));

        assertTrue(repositoryProvider.repositoryPath("orion").resolve("config").toFile().exists());
        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load from newly created local repository");
        assertEquals(1, snapshot.files().size());
        assertTrue(snapshot.files().containsKey(ACL_FILE));
        assertEquals("initial acl", new String(snapshot.files().get(ACL_FILE), StandardCharsets.UTF_8));
        assertEquals(1, repositoryProvider.findOrCreateCalls);
        assertEquals(1, repositoryProvider.findCalls);
    }

    @Test
    void localAclRepositoryNamesAreResolvedFromSupportedSchemes() {
        Map<String, String> expectedRepositoryNames = Map.of(
                "local:acl", "acl",
                "local:team/project", Path.of("team/project").toString(),
                "local://team/project", Path.of("team/project").toString());

        for (Map.Entry<String, String> entry : expectedRepositoryNames.entrySet()) {
            assertTrue(AccessControlStorageResolver.isInternalLocalGitStorage(entry.getKey()));
            assertEquals(entry.getValue(), AccessControlStorageResolver.localRepositoryName(entry.getKey()));
        }
        assertFalse(AccessControlStorageResolver.isInternalLocalGitStorage("ssh://git@example.test/acl.git"));
    }

    @Test
    void remoteGitAclIsUnsupportedUntilRemoteVersionedStorageIsAdded() {
        OrionConfiguration configuration = configurationWithGitAcl("ssh://git@example.test/acl.git");
        FileGitRepositoryProvider repositoryProvider = new FileGitRepositoryProvider(new ConfigurationContext(configuration));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeAccessControlStorage(configuration, repositoryProvider));

        assertEquals("Unsupported ACL location: ssh://git@example.test/acl.git", error.getMessage());
    }

    private AccessControlStorage runtimeAccessControlStorage(OrionConfiguration configuration,
                                                             GitRepositoryProvider repositoryProvider) {
        return new AccessControlStorageResolver(configuration, repositoryProvider).resolve();
    }

    private void seedBareRepository(Path bareRepository, String userId) throws Exception {
        Files.createDirectories(bareRepository.getParent());
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(bareRepository.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
        }

        Path seedWorktree = Files.createTempDirectory(tempDir, userId + "-seed-");
        try (Git seed = Git.init()
                .setDirectory(seedWorktree.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            Files.write(seedWorktree.resolve(ACL_FILE), aclBytes(userId));
            seed.add().addFilepattern(ACL_FILE).call();
            seed.commit()
                    .setAuthor("ACL Test", "acl@example.test")
                    .setCommitter("ACL Test", "acl@example.test")
                    .setMessage("seed ACL")
                    .call();
            seed.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
        }
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
        AccessControl accessControl = xmlService.deserialize(new ByteArrayInputStream(snapshot.files().get(ACL_FILE)));
        assertEquals(1, accessControl.getUsers().size());
        assertEquals(userId, accessControl.getUsers().getFirst().getId());
    }

    private OrionConfiguration configurationWithGitAcl(String location) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getAccessControl().setBranch(BRANCH);
        configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));
        return configuration;
    }

    private OrionConfiguration defaultRuntimeConfiguration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repos").toUri().toString());
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttp().setEnabled(false);
        configuration.getTransport().getHttps().setEnabled(false);
        return configuration;
    }

    private OrionConfiguration runtimeConfigurationWithEnabledTransports() {
        OrionConfiguration configuration = defaultRuntimeConfiguration();
        configuration.getTransport().getGit().setEnabled(true);
        configuration.getTransport().getSsh().setEnabled(true);
        configuration.getTransport().getHttp().setEnabled(true);
        configuration.getTransport().getHttps().setEnabled(true);
        return configuration;
    }

    private OrionComponent runtimeComponent(OrionConfiguration configuration) {
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .build();
    }

    private static boolean containsType(List<String> parameterTypes, String value) {
        for (String parameterType : parameterTypes) {
            if (parameterType.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static final class RecordingGitRepositoryProvider extends FileGitRepositoryProvider {
        private int findCalls;
        private int findOrCreateCalls;

        private RecordingGitRepositoryProvider(OrionConfiguration configuration) {
            super(new ConfigurationContext(configuration));
        }

        @Override
        public Result<GitRepository> find(String repositoryName) {
            findCalls++;
            return super.find(repositoryName);
        }

        @Override
        public Result<GitRepository> findOrCreate(String repositoryName) {
            findOrCreateCalls++;
            return super.findOrCreate(repositoryName);
        }
    }

}
