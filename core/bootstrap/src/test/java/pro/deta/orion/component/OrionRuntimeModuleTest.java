package pro.deta.orion.component;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.acl.storage.JDBCAccessControlStorage;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.acl.storage.LocalGitAccessControlStorage;
import pro.deta.orion.acl.storage.VersionedAccessControlStorage;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.git.GitRepositoryProviderImpl;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateHolder;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionRuntimeModuleTest {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "acl.xml";
    private static final String TEST_PASSWORD = "acl-password";
    private static final String TEST_PASSWORD_HASH = "acl-password-hash";

    @TempDir
    private Path tempDir;

    private final XmlService xmlService = new XmlService();

    @Test
    void localGitAreaAclUsesVersionedStorageWithoutLegacyGitAccessArea() {
        OrionConfiguration configuration = configurationWithGitAcl("local:team/project");
        GitRepositoryProviderImpl repositoryProvider = new GitRepositoryProviderImpl(new ConfigurationContext(configuration));

        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        assertEquals(VersionedAccessControlStorage.class, storage.getClass());
        assertEquals(ACL_FILE, storage.primaryPath());
    }

    @Test
    void independentGitDirectoryAclStartsFromFileRepository() throws Exception {
        Path independentRepository = tempDir.resolve("independent-acl.git");
        seedBareRepository(independentRepository, "independent-user");
        OrionConfiguration configuration = configurationWithGitAcl(independentRepository.toUri().toString());
        GitRepositoryProviderImpl repositoryProvider = new GitRepositoryProviderImpl(new ConfigurationContext(configuration));
        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        assertInstanceOf(LocalGitAccessControlStorage.class, storage);
        assertUserAuthenticates(service, "independent-user");
    }

    @Test
    void gitAreaAclStartsFromVersionedStorage() throws Exception {
        OrionConfiguration configuration = configurationWithGitAcl("local:team/project");
        RecordingGitRepositoryProvider repositoryProvider = new RecordingGitRepositoryProvider(configuration);
        seedBareRepository(repositoryProvider.repositoryPath("team/project"), "area-user");
        AccessControlStorage storage = runtimeAccessControlStorage(configuration, repositoryProvider);

        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        assertEquals(VersionedAccessControlStorage.class, storage.getClass());
        assertEquals(1, repositoryProvider.findCalls);
        assertEquals(0, repositoryProvider.findOrCreateCalls);
        assertUserAuthenticates(service, "area-user");
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
        assertTrue(AccessControlStorageResolver.isIndependentLocalGitStorage(tempDir.resolve("plain-acl.git").toString()));
        assertTrue(AccessControlStorageResolver.isIndependentLocalGitStorage(tempDir.resolve("file-acl.git").toUri().toString()));
        assertFalse(AccessControlStorageResolver.isInternalLocalGitStorage("ssh://git@example.test/acl.git"));
    }

    @Test
    void remoteGitAclIsUnsupportedUntilRemoteVersionedStorageIsAdded() {
        OrionConfiguration configuration = configurationWithGitAcl("ssh://git@example.test/acl.git");
        GitRepositoryProviderImpl repositoryProvider = new GitRepositoryProviderImpl(new ConfigurationContext(configuration));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeAccessControlStorage(configuration, repositoryProvider));

        assertEquals("Unsupported Git ACL location: ssh://git@example.test/acl.git", error.getMessage());
    }

    private AccessControlStorage runtimeAccessControlStorage(OrionConfiguration configuration,
                                                             GitRepositoryProviderImpl repositoryProvider) {
        return new AccessControlStorageResolver(
                configuration,
                repositoryProvider,
                new JDBCAccessControlStorage(configuration.getAccessControl()),
                new LocalAccessControlStorage(configuration.getAccessControl()))
                .resolve();
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
        AccessControl accessControl = new AccessControl();
        accessControl.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.ARGON2, TEST_PASSWORD_HASH));
        return accessControl;
    }

    private OrionAccessControlServiceImpl startAccessControlService(AccessControlStorage storage) throws Exception {
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory())) {
            OrionProvider provider = new OrionProvider(new ApplicationStateHolder(), () -> null, () -> null, () -> executor);
            OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(
                    storage,
                    new FixedPasswordHashingService(),
                    provider);
            startWithoutRootPasswordOutput(service);
            return service;
        }
    }

    private void startWithoutRootPasswordOutput(OrionAccessControlServiceImpl service) throws Exception {
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            OrionStageCallResult result = service.onStart();
            waitForStageTasks(result);
        } finally {
            System.setOut(originalOut);
        }
    }

    private void waitForStageTasks(OrionStageCallResult result) throws Exception {
        for (var future : result.getFuturesToWait()) {
            future.getFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private void assertUserAuthenticates(OrionAccessControlServiceImpl service, String userId) {
        AuthenticationResult result = service.authenticateUser(
                userId,
                AccessControl.CredentialType.ARGON2,
                TEST_PASSWORD.getBytes(StandardCharsets.UTF_8));

        AuthenticationResult.Success success = assertInstanceOf(AuthenticationResult.Success.class, result);
        assertEquals(userId, success.userIdentity().getUserId());
    }

    private OrionConfiguration configurationWithGitAcl(String location) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.setBaseDir(tempDir.toString());
        configuration.getGit().setStoragePath("repos");
        configuration.getAccessControl().setType(OrionConfiguration.ACLStorageType.GIT);
        configuration.getAccessControl().setUrl(location);
        configuration.getAccessControl().setBranch(BRANCH);
        configuration.getAccessControl().setSettingsFileName(ACL_FILE);
        return configuration;
    }

    private static final class RecordingGitRepositoryProvider extends GitRepositoryProviderImpl {
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

    private static final class FixedPasswordHashingService extends OrionPasswordHashingService {
        @Override
        public char[] generateRandomString(int length) {
            return TEST_PASSWORD.toCharArray();
        }

        @Override
        public String calculateHash(char[] password) {
            assertEquals(TEST_PASSWORD, String.valueOf(password));
            return TEST_PASSWORD_HASH;
        }

        @Override
        public boolean comparePassword(String expected, byte[] provided) {
            return TEST_PASSWORD_HASH.equals(expected)
                    && TEST_PASSWORD.equals(new String(provided, StandardCharsets.UTF_8));
        }
    }
}
