package pro.deta.orion.acl.storage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateHolder;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.OrionProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.KeyPair;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControlStorageTest {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "config/orion.xml";
    private static final String DEFAULT_ROOT_PASSWORD = "root-password";
    private static final String DEFAULT_ROOT_PASSWORD_HASH = "fixed-root-password-hash";

    @TempDir
    private Path tempDir;

    private final XmlService xmlService = new XmlService();

    @Test
    void localStorageLoadsAclFromFile() throws Exception {
        Path aclDirectory = tempDir.resolve("acl-directory");
        Files.createDirectories(aclDirectory.resolve("config"));
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytes("file-user"));

        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load from local file");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_FILE);
        assertThat(userIds(snapshot)).containsExactly("file-user");
    }

    @Test
    void localStorageSavesAclToFile() throws Exception {
        Path aclDirectory = tempDir.resolve("acl-directory");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, aclBytes("saved-file-user")),
                new AccessControlSaveRequest("save local ACL", new UserEmail("tester", "tester@example.test")));

        assertThat(aclDirectory.resolve(ACL_FILE)).exists();
        assertThat(userIds(Files.readAllBytes(aclDirectory.resolve(ACL_FILE)))).containsExactly("saved-file-user");
    }

    @Test
    void localStorageOverwritesAclFile() throws Exception {
        Path aclDirectory = tempDir.resolve("overwrite-acl-directory");
        Files.createDirectories(aclDirectory.resolve("config"));
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytes("old-file-user"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, aclBytes("new-file-user")),
                new AccessControlSaveRequest("overwrite local ACL", new UserEmail("tester", "tester@example.test")));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load after local overwrite");
        assertThat(userIds(snapshot)).containsExactly("new-file-user");
        assertThat(userIds(Files.readAllBytes(aclDirectory.resolve(ACL_FILE)))).containsExactly("new-file-user");
    }

    @Test
    void localStorageCreatesInitialAclConfiguration() throws Exception {
        Path aclDirectory = tempDir.resolve("initial-acl-directory");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        assertThat(aclDirectory.resolve(ACL_FILE)).exists();
        AccessControlSnapshot snapshot = storage.load().valueOrFailure("Initial ACL should be saved to local file");
        assertDefaultAccessControl(snapshot);
        assertRootAuthenticates(service);
        assertPlainRootTokenAvailableToTests(service);
    }

    @Test
    void localGitStorageLoadsAclFromRepository() throws Exception {
        SeededRepository seededRepository = seedBareRepository("git-user");
        LocalGitAccessControlStorage storage = new LocalGitAccessControlStorage(localGitConfig(seededRepository.repositoryPath()));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load from local git repository");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_FILE);
        assertThat(snapshot.version()).contains(seededRepository.commitId());
        assertThat(userIds(snapshot)).containsExactly("git-user");
    }

    @Test
    void localGitStorageSavesAclToRepository() throws Exception {
        Path repositoryPath = createBareRepository("saved-acl.git");
        LocalGitAccessControlStorage storage = new LocalGitAccessControlStorage(localGitConfig(repositoryPath));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, aclBytes("saved-git-user")),
                new AccessControlSaveRequest("save git ACL", new UserEmail("tester", "tester@example.test")));

        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            ObjectId branchHead = repository.resolve("refs/heads/" + BRANCH);
            assertThat(branchHead).isNotNull();
        }
        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load after local git save");
        assertThat(userIds(snapshot)).containsExactly("saved-git-user");
    }

    @Test
    void localGitStorageOverwritesAclInRepository() throws Exception {
        SeededRepository seededRepository = seedBareRepository("old-git-user");
        LocalGitAccessControlStorage storage = new LocalGitAccessControlStorage(localGitConfig(seededRepository.repositoryPath()));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, aclBytes("new-git-user")),
                new AccessControlSaveRequest("overwrite git ACL", new UserEmail("tester", "tester@example.test")));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load after local git overwrite");
        assertThat(snapshot.version()).isPresent();
        assertThat(snapshot.version().orElseThrow()).isNotEqualTo(seededRepository.commitId());
        assertThat(userIds(snapshot)).containsExactly("new-git-user");
    }

    @Test
    void localGitStorageCreatesInitialAclConfiguration() throws Exception {
        Path repositoryPath = createBareRepository("initial-acl.git");
        LocalGitAccessControlStorage storage = new LocalGitAccessControlStorage(localGitConfig(repositoryPath));

        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("Initial ACL should be saved to local git repository");
        assertThat(snapshot.version()).isPresent();
        assertDefaultAccessControl(snapshot);
        assertRootAuthenticates(service);
    }

    @Test
    void plainRootTokenRequiresTestAccess() throws Exception {
        Path aclDirectory = tempDir.resolve("plain-token-access");
        OrionAccessControlServiceImpl service = startAccessControlService(new LocalAccessControlStorage(localConfig(aclDirectory)));

        assertThatThrownBy(() -> service.plainRootToken(null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Plain root token access is required");
    }

    @Test
    void plainRootTokenReturnsDefensiveCopy() throws Exception {
        Path aclDirectory = tempDir.resolve("plain-token-copy");
        OrionAccessControlServiceImpl service = startAccessControlService(new LocalAccessControlStorage(localConfig(aclDirectory)));

        char[] returnedToken = service.plainRootToken(PlainRootTokenAccessForTests.create());
        returnedToken[0] = 'X';

        assertThat(String.valueOf(service.plainRootToken(PlainRootTokenAccessForTests.create())))
                .isEqualTo(DEFAULT_ROOT_PASSWORD);
    }

    @Test
    void accessControlServiceCreatesAndUpdatesManagedUser() throws Exception {
        Path aclDirectory = tempDir.resolve("managed-user-acl");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));
        OrionAccessControlServiceImpl service = startAccessControlService(storage);
        KeyPair userKey = KeyUtils.generateRSAKeyPair().valueOrFailure("User key should be generated");

        service.createOrUpdateUser(new AccessControlUserUpdate(
                "client",
                "client@example.test",
                List.of(new AccessControlCredentialUpdate(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        KeyUtils.publicKeyToString(userKey.getPublic()))),
                List.of(new AccessControlRepositoryGrantUpdate("project", true, true, true, false, "*"))));

        AuthenticationResult result = service.authenticateUser(
                "client",
                AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                userKey.getPublic().getEncoded());
        assertThat(result).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success success = (AuthenticationResult.Success) result;
        assertThat(success.userIdentity().getUserId()).isEqualTo("client");
        assertThat(repositoryNames(success.userIdentity().getGrants())).containsExactly("project");
        assertThat(grantInfo(success.userIdentity().getGrants().getFirst()))
                .containsEntry(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .containsEntry(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING)
                .containsEntry(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING);

        AccessControl savedAccessControl = accessControlFrom(storage.load().valueOrFailure("ACL should be saved"));
        assertThat(userIds(savedAccessControl)).containsExactly("root", "client");

        service.createOrUpdateUser(new AccessControlUserUpdate(
                "client",
                "client@example.test",
                List.of(new AccessControlCredentialUpdate(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        KeyUtils.publicKeyToString(userKey.getPublic()))),
                List.of(new AccessControlRepositoryGrantUpdate("other-project", true, false, false, false, "main"))));

        AccessControl updatedAccessControl = accessControlFrom(storage.load().valueOrFailure("Updated ACL should be saved"));
        assertThat(userIds(updatedAccessControl)).containsExactly("root", "client");

        AuthenticationResult updatedResult = service.authenticateUser(
                "client",
                AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                userKey.getPublic().getEncoded());
        assertThat(updatedResult).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success updatedSuccess = (AuthenticationResult.Success) updatedResult;
        assertThat(repositoryNames(updatedSuccess.userIdentity().getGrants())).containsExactly("other-project");
        assertThat(grantInfo(updatedSuccess.userIdentity().getGrants().getFirst()))
                .containsEntry(AccessControl.GrantKey.BRANCH, "main")
                .doesNotContainKeys(AccessControl.GrantKey.WRITE, AccessControl.GrantKey.CREATE);
    }

    private OrionConfiguration.AccessControlConfig localConfig(Path directory) {
        OrionConfiguration.AccessControlConfig config = new OrionConfiguration.AccessControlConfig();
        config.setType(OrionConfiguration.ACLStorageType.LOCAL);
        config.setUrl(directory.toUri().toString());
        config.setSettingsFileName(ACL_FILE);
        return config;
    }

    private OrionConfiguration.AccessControlConfig localGitConfig(Path repositoryPath) {
        OrionConfiguration.AccessControlConfig config = new OrionConfiguration.AccessControlConfig();
        config.setType(OrionConfiguration.ACLStorageType.GIT);
        config.setUrl(repositoryPath.toUri().toString());
        config.setBranch(BRANCH);
        config.setSettingsFileName(ACL_FILE);
        return config;
    }

    private SeededRepository seedBareRepository(String userId) throws Exception {
        Path bareRepository = createBareRepository(userId + ".git");
        Path seedWorktree = tempDir.resolve(userId + "-seed");

        try (Git seed = Git.init()
                .setDirectory(seedWorktree.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            Files.createDirectories(seedWorktree.resolve("config"));
            Files.write(seedWorktree.resolve(ACL_FILE), aclBytes(userId));
            seed.add().addFilepattern(ACL_FILE).call();
            ObjectId commitId = seed.commit()
                    .setAuthor("ACL Test", "acl@example.test")
                    .setCommitter("ACL Test", "acl@example.test")
                    .setMessage("seed ACL")
                    .call()
                    .toObjectId();
            seed.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
            return new SeededRepository(bareRepository, commitId.name());
        }
    }

    private Path createBareRepository(String repositoryName) throws Exception {
        Path repositoryPath = tempDir.resolve(repositoryName);
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(repositoryPath.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            return repositoryPath;
        }
    }

    private byte[] aclBytes(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControlWithUser(userId), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        AccessControl accessControl = new AccessControl();
        accessControl.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test"));
        return accessControl;
    }

    private List<String> userIds(AccessControlSnapshot snapshot) throws Exception {
        return userIds(snapshot.files().get(ACL_FILE));
    }

    private List<String> userIds(AccessControl accessControl) {
        List<String> userIds = new ArrayList<>();
        for (AccessControl.User user : accessControl.getUsers()) {
            userIds.add(user.getId());
        }
        return userIds;
    }

    private List<String> userIds(byte[] content) throws Exception {
        AccessControl accessControl = accessControlFrom(content);
        return userIds(accessControl);
    }

    private AccessControl accessControlFrom(AccessControlSnapshot snapshot) throws Exception {
        return accessControlFrom(snapshot.files().get(ACL_FILE));
    }

    private AccessControl accessControlFrom(byte[] content) throws Exception {
        AccessControl accessControl = xmlService.deserialize(new ByteArrayInputStream(content));
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
        synchronized (AccessControlStorageTest.class) {
            PrintStream originalOut = System.out;
            try {
                System.setOut(new PrintStream(OutputStream.nullOutputStream()));
                OrionStageCallResult result = service.onStart();
                waitForStageTasks(result);
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    private void waitForStageTasks(OrionStageCallResult result) throws Exception {
        for (var future : result.getFuturesToWait()) {
            future.getFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private void assertDefaultAccessControl(AccessControlSnapshot snapshot) throws Exception {
        AccessControl accessControl = accessControlFrom(snapshot);

        assertThat(userIds(snapshot)).containsExactly("root");
        AccessControl.User rootUser = accessControl.getUsers().getFirst();
        assertThat(rootUser.getEmail()).isEqualTo("root@orion.pro");
        assertThat(rootUser.getRoles()).containsExactly("ROOT");
        assertThat(credentialValues(rootUser))
                .hasSize(2)
                .containsEntry(AccessControl.CredentialType.ARGON2, DEFAULT_ROOT_PASSWORD_HASH)
                .containsEntry(AccessControl.CredentialType.BEARER_TOKEN, DEFAULT_ROOT_PASSWORD_HASH);

        assertThat(roleIds(accessControl)).containsExactly("ROOT");
        AccessControl.Role rootRole = accessControl.getRoles().getFirst();
        assertThat(rootRole.getGrantReferences()).containsExactly("CONNECT", "ALL_REPOSITORY", "APPLICATION_CONTROL");

        assertThat(grantIds(accessControl)).containsExactly("CONNECT", "ALL_REPOSITORY", "APPLICATION_CONTROL");
        assertThat(grantInfo(grantById(accessControl, "ALL_REPOSITORY")))
                .containsEntry(AccessControl.GrantKey.REPOSITORY, "*")
                .containsEntry(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .containsEntry(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING)
                .containsEntry(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING)
                .containsEntry(AccessControl.GrantKey.BRANCH, "*")
                .containsEntry(AccessControl.GrantKey.FORCE, AccessControl.TRUE_STRING);
        assertThat(grantInfo(grantById(accessControl, "CONNECT")))
                .containsEntry(AccessControl.GrantKey.NETWORK_SOURCE, "127.0.0.1");
        assertThat(grantInfo(grantById(accessControl, "APPLICATION_CONTROL")))
                .containsEntry(AccessControl.GrantKey.SHUTDOWN, AccessControl.TRUE_STRING)
                .containsEntry(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING);
    }

    private void assertRootAuthenticates(OrionAccessControlServiceImpl service) {
        AuthenticationResult result = service.authenticateUser(
                "root",
                AccessControl.CredentialType.ARGON2,
                DEFAULT_ROOT_PASSWORD.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success success = (AuthenticationResult.Success) result;
        assertThat(success.userIdentity().getUserId()).isEqualTo("root");
        assertThat(grantIds(success.userIdentity().getGrants())).containsExactly(
                "CONNECT",
                "ALL_REPOSITORY",
                "APPLICATION_CONTROL");

        AuthenticationResult bearerResult = service.authenticateUser(
                "root",
                AccessControl.CredentialType.BEARER_TOKEN,
                DEFAULT_ROOT_PASSWORD.getBytes(StandardCharsets.UTF_8));

        assertThat(bearerResult).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success bearerSuccess = (AuthenticationResult.Success) bearerResult;
        assertThat(bearerSuccess.userIdentity().getUserId()).isEqualTo("root");
    }

    private void assertPlainRootTokenAvailableToTests(OrionAccessControlServiceImpl service) {
        assertThat(String.valueOf(service.plainRootToken(PlainRootTokenAccessForTests.create())))
                .isEqualTo(DEFAULT_ROOT_PASSWORD);
    }

    private List<String> roleIds(AccessControl accessControl) {
        List<String> roleIds = new ArrayList<>();
        for (AccessControl.Role role : accessControl.getRoles()) {
            roleIds.add(role.getId());
        }
        return roleIds;
    }

    private List<String> grantIds(AccessControl accessControl) {
        return grantIds(accessControl.getGrants());
    }

    private List<String> grantIds(List<AccessControl.Grant> grants) {
        List<String> grantIds = new ArrayList<>();
        for (AccessControl.Grant grant : grants) {
            grantIds.add(grant.getId());
        }
        return grantIds;
    }

    private AccessControl.Grant grantById(AccessControl accessControl, String grantId) {
        for (AccessControl.Grant grant : accessControl.getGrants()) {
            if (grantId.equals(grant.getId())) {
                return grant;
            }
        }
        throw new IllegalStateException("Missing grant " + grantId);
    }

    private Map<AccessControl.GrantKey, String> grantInfo(AccessControl.Grant grant) {
        Map<AccessControl.GrantKey, String> info = new LinkedHashMap<>();
        for (AccessControl.GrantExpression expression : grant.getInfo()) {
            info.put(expression.getKey(), expression.getValue());
        }
        return info;
    }

    private Map<AccessControl.CredentialType, String> credentialValues(AccessControl.User user) {
        Map<AccessControl.CredentialType, String> credentials = new LinkedHashMap<>();
        for (AccessControl.Credential credential : user.getCredentials()) {
            credentials.put(credential.getType(), credential.getValue());
        }
        return credentials;
    }

    private List<String> repositoryNames(List<AccessControl.Grant> grants) {
        List<String> names = new ArrayList<>();
        for (AccessControl.Grant grant : grants) {
            for (AccessControl.GrantExpression expression : grant.getInfo()) {
                if (expression.getKey() == AccessControl.GrantKey.REPOSITORY) {
                    names.add(expression.getValue());
                }
            }
        }
        return names;
    }

    private static final class FixedPasswordHashingService extends OrionPasswordHashingService {
        @Override
        public char[] generateRandomString(int length) {
            return DEFAULT_ROOT_PASSWORD.toCharArray();
        }

        @Override
        public String calculateHash(char[] password) {
            assertThat(String.valueOf(password)).isEqualTo(DEFAULT_ROOT_PASSWORD);
            return DEFAULT_ROOT_PASSWORD_HASH;
        }

        @Override
        public boolean comparePassword(String expected, byte[] provided) {
            return DEFAULT_ROOT_PASSWORD_HASH.equals(expected)
                    && DEFAULT_ROOT_PASSWORD.equals(new String(provided, StandardCharsets.UTF_8));
        }
    }

    private record SeededRepository(Path repositoryPath, String commitId) {
    }
}
