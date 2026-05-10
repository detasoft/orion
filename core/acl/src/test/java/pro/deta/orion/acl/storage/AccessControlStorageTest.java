package pro.deta.orion.acl.storage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
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
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.crypto.PublicKeysProvider;
import pro.deta.orion.crypto.ServerIdentityKeyService;
import pro.deta.orion.crypto.ServerKeySigner;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.RequestToAclUpdate;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateHolder;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.KeyPair;
import java.security.PublicKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;

class AccessControlStorageTest {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "config/orion.xml";
    private static final String DEFAULT_ROOT_PASSWORD = "root-password";
    private static final String DEFAULT_ROOT_PASSWORD_HASH = "be1ec2bc9b9735be8fc708736e8e74a5bd46af75";
    private static final String JWT_SIGNING_KEY_ID = "laptop-2026";
    private static final String JWT_SIGNING_PUBLIC_KEY = "ssh-ed25519 AAAATEST token-client@example.test";

    @TempDir
    private Path tempDir;

    private final XmlService xmlService = new XmlService();
    private final List<TestProviderContext> providerContexts = new ArrayList<>();

    @AfterEach
    void closeProviderContexts() {
        for (TestProviderContext context : providerContexts) {
            context.close();
        }
        providerContexts.clear();
    }

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
    void accessControlServiceDoesNotCreateInitialAclForStorageErrors() {
        AccessControlStorage storage = new AccessControlStorage() {
            @Override
            public Result<AccessControlSnapshot> load() {
                return new Result.Failure<>(
                        Result.FailureCode.GENERAL,
                        "storage unavailable",
                        new IllegalStateException("storage unavailable"));
            }

            @Override
            public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
                throw new AssertionError("Storage errors must not create default ACL");
            }

            @Override
            public String primaryPath() {
                return ACL_FILE;
            }
        };

        assertThatThrownBy(() -> startAccessControlService(storage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configuration repository not initialized");
    }

    @Test
    void accessControlServiceDoesNotCreateInitialAclWhenDisabled() {
        Path aclDirectory = tempDir.resolve("default-disabled-acl");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));
        OrionConfiguration configuration = testConfiguration();
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);

        assertThatThrownBy(() -> startAccessControlService(storage, configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configuration repository not initialized");
        assertThat(aclDirectory.resolve(ACL_FILE)).doesNotExist();
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
    void accessControlServiceReturnsPrimaryConfigurationFileContent() throws Exception {
        Path aclDirectory = tempDir.resolve("raw-acl-directory");
        Files.createDirectories(aclDirectory.resolve("config"));
        byte[] aclContent = aclBytes("raw-file-user");
        Files.write(aclDirectory.resolve(ACL_FILE), aclContent);
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        assertThat(service.accessControlConfigurationFile()).isEqualTo(aclContent);
    }

    @Test
    void accessControlServiceSavesPrimaryConfigurationFileContentAndReloadsAcl() throws Exception {
        Path aclDirectory = tempDir.resolve("raw-save-acl-directory");
        Files.createDirectories(aclDirectory.resolve("config"));
        byte[] initialAclContent = aclBytesWithPasswordUser("initial-raw-file-user");
        Files.write(aclDirectory.resolve(ACL_FILE), initialAclContent);
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));
        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        byte[] updatedAclContent = aclBytesWithPasswordUser("updated-raw-file-user");
        service.saveAccessControlConfigurationFile(updatedAclContent);

        assertThat(storage.load().valueOrFailure("Updated ACL should be saved").files().get(ACL_FILE))
                .isEqualTo(updatedAclContent);
        assertThat(service.accessControlConfigurationFile()).isEqualTo(updatedAclContent);
        assertThat(service.authenticateUser(
                "initial-raw-file-user",
                "password".getBytes(StandardCharsets.UTF_8))).isInstanceOf(AuthenticationResult.Failure.class);
        assertUserAuthenticates(service, "updated-raw-file-user");
    }

    @Test
    void defaultRootAuthenticatesWithInternalServerKey() throws Exception {
        Path aclDirectory = tempDir.resolve("server-key-root-acl");
        OrionConfiguration configuration = testConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("server-key-root-base").toString());
        ServerIdentityKeyService serverIdentityKeyService = new ServerIdentityKeyService(new ConfigurationContext(configuration));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));
        OrionAccessControlServiceImpl service = startAccessControlService(storage, configuration, serverIdentityKeyService);
        AccessControl savedAccessControl = accessControlFrom(storage.load()
                .valueOrFailure("ACL should be saved with internal server keys"));
        AccessControl.User rootUser = savedAccessControl.getUsers().getFirst();

        for (PublicKey internalServerKey : serverIdentityKeyService.getPublicKeys()) {
            assertThat(hasPublicKeyCredential(rootUser, internalServerKey)).isTrue();

            AuthenticationResult rootResult = service.authenticateUser(
                    "root",
                    internalServerKey.getEncoded());
            assertThat(rootResult).isInstanceOf(AuthenticationResult.Success.class);
            AuthenticationResult.Success rootSuccess = (AuthenticationResult.Success) rootResult;

            assertThat(rootSuccess.userIdentity().getUserId()).isEqualTo("root");
        }

        KeyPair unrelatedKey = KeyUtils.generateRSAKeyPair().valueOrFailure("Unrelated key should be generated");
        assertThat(service.authenticateUser(
                "root",
                unrelatedKey.getPublic().getEncoded())).isInstanceOf(AuthenticationResult.Failure.class);
        assertThat(service.authenticateUser(
                "client",
                serverIdentityKeyService.getPublicKeys().getFirst().getEncoded())).isInstanceOf(AuthenticationResult.Failure.class);
    }

    @Test
    void authenticatedRootCanIssueBearerTokenWithInternalServerKey() throws Exception {
        Path aclDirectory = tempDir.resolve("server-key-authenticated-token-acl");
        OrionConfiguration configuration = testConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("server-key-authenticated-token-base").toString());
        ServerIdentityKeyService serverIdentityKeyService = new ServerIdentityKeyService(new ConfigurationContext(configuration));
        OrionAccessControlServiceImpl service = startAccessControlService(
                new LocalAccessControlStorage(localConfig(aclDirectory)),
                configuration,
                serverIdentityKeyService);

        AuthenticationResult authenticationResult = service.authenticateUser(
                "root",
                serverIdentityKeyService.getPublicKeys().getFirst().getEncoded());

        assertThat(authenticationResult).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success authentication = (AuthenticationResult.Success) authenticationResult;
        TokenIssueResult issueResult = service.issueTokenFor(authentication.userIdentity(), 600);

        assertThat(issueResult).isInstanceOf(TokenIssueResult.Success.class);
        TokenIssueResult.Success issuedToken = (TokenIssueResult.Success) issueResult;
        assertThat(issuedToken.token()).isNotBlank();
        assertThat(service.authenticateToken(issuedToken.token().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }

    @Test
    void defaultRootCanIssueAndAuthenticateBearerToken() throws Exception {
        Path aclDirectory = tempDir.resolve("server-key-token-acl");
        OrionConfiguration configuration = testConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("server-key-token-base").toString());
        ServerIdentityKeyService serverIdentityKeyService = new ServerIdentityKeyService(new ConfigurationContext(configuration));
        OrionAccessControlServiceImpl service = startAccessControlService(
                new LocalAccessControlStorage(localConfig(aclDirectory)),
                configuration,
                serverIdentityKeyService);

        TokenIssueResult issueResult = service.authenticateUserAndIssueToken(
                "root",
                String.valueOf(service.plainRootToken(PlainRootTokenAccessForTests.create())).getBytes(StandardCharsets.UTF_8),
                600);

        assertThat(issueResult).isInstanceOf(TokenIssueResult.Success.class);
        TokenIssueResult.Success issuedToken = (TokenIssueResult.Success) issueResult;
        assertThat(issuedToken.token()).isNotBlank();
        assertThat(issuedToken.expiresAtEpochSecond()).isGreaterThan(0);

        AuthenticationResult authenticationResult = service.authenticateToken(
                issuedToken.token().getBytes(StandardCharsets.UTF_8));
        assertThat(authenticationResult).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success authentication = (AuthenticationResult.Success) authenticationResult;
        assertThat(authentication.userIdentity().getUserId()).isEqualTo("root");
    }

    @Test
    void existingRootReceivesInternalServerKeysOnLoad() throws Exception {
        Path aclDirectory = tempDir.resolve("existing-server-key-root-acl");
        Files.createDirectories(aclDirectory.resolve("config"));
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytesWithPasswordUser("root"));
        OrionConfiguration configuration = testConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("existing-server-key-root-base").toString());
        ServerIdentityKeyService serverIdentityKeyService = new ServerIdentityKeyService(new ConfigurationContext(configuration));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        OrionAccessControlServiceImpl service = startAccessControlService(storage, configuration, serverIdentityKeyService);

        AccessControl savedAccessControl = accessControlFrom(storage.load()
                .valueOrFailure("Existing ACL should be updated with internal server keys"));
        AccessControl.User rootUser = savedAccessControl.getUsers().getFirst();
        for (PublicKey internalServerKey : serverIdentityKeyService.getPublicKeys()) {
            assertThat(hasPublicKeyCredential(rootUser, internalServerKey)).isTrue();
            assertThat(service.authenticateUser(
                    "root",
                    internalServerKey.getEncoded())).isInstanceOf(AuthenticationResult.Success.class);
        }
    }

    @Test
    void accessControlServiceRegistersReloadHandlerWhenAclLoads() throws Exception {
        Path aclDirectory = tempDir.resolve("reload-acl-directory");
        Files.createDirectories(aclDirectory.resolve("config"));
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytesWithPasswordUser("initial-user"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));

        OrionConfiguration configuration = testConfiguration();
        TestProviderContext providerContext = startedProviderContext();
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(
                storage,
                new FixedPasswordHashingService(),
                providerContext.orionProvider(),
                configuration,
                PublicKeysProvider.DEFAULT,
                ServerKeySigner.DEFAULT) {
            @Override
            protected PasswordHashingAlgorithm defaultPasswordHashingAlgorithm() {
                return SHA1;
            }
        };
        waitForStageTasks(service.aclLoad());
        assertUserAuthenticates(service, "initial-user");

        Files.write(aclDirectory.resolve(ACL_FILE), aclBytesWithPasswordUser("reloaded-user"));
        RequestToAclUpdate event = new RequestToAclUpdate("test");
        providerContext.eventManager().publish(event);

        assertThat(OrionUtils.waitForCondition(event::isProcessed, 5, TimeUnit.SECONDS)).isTrue();
        assertUserAuthenticates(service, "reloaded-user");
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
                userKey.getPublic().getEncoded());
        assertThat(updatedResult).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success updatedSuccess = (AuthenticationResult.Success) updatedResult;
        assertThat(repositoryNames(updatedSuccess.userIdentity().getGrants())).containsExactly("other-project");
        assertThat(grantInfo(updatedSuccess.userIdentity().getGrants().getFirst()))
                .containsEntry(AccessControl.GrantKey.BRANCH, "main")
                .doesNotContainKeys(AccessControl.GrantKey.WRITE, AccessControl.GrantKey.CREATE);
    }

    @Test
    void accessControlServiceCreatesManagedUserWithSha1Password() throws Exception {
        Path aclDirectory = tempDir.resolve("managed-password-user-acl");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));
        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        service.createOrUpdateUser(new AccessControlUserUpdate(
                "password-client",
                "password-client@example.test",
                List.of(new AccessControlCredentialUpdate(
                        AccessControl.CredentialType.SHA1,
                        DEFAULT_ROOT_PASSWORD_HASH)),
                List.of(new AccessControlRepositoryGrantUpdate("project", true, false, false, false, "*"))));

        AuthenticationResult passwordResult = service.authenticateUser(
                "password-client",
                DEFAULT_ROOT_PASSWORD.getBytes(StandardCharsets.UTF_8));
        assertThat(passwordResult).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success passwordSuccess = (AuthenticationResult.Success) passwordResult;
        assertThat(passwordSuccess.userIdentity().getUserId()).isEqualTo("password-client");
        assertThat(repositoryNames(passwordSuccess.userIdentity().getGrants())).containsExactly("project");
        assertThat(service.authenticateUser(
                "password-client",
                "other-password".getBytes(StandardCharsets.UTF_8))).isInstanceOf(AuthenticationResult.Failure.class);

        AccessControl savedAccessControl = accessControlFrom(storage.load().valueOrFailure("ACL should be saved"));
        AccessControl.User savedUser = userById(savedAccessControl, "password-client");
        assertThat(credentialValues(savedUser))
                .containsEntry(AccessControl.CredentialType.SHA1, DEFAULT_ROOT_PASSWORD_HASH)
                .doesNotContainKey(AccessControl.CredentialType.ARGON2);
    }

    @Test
    void accessControlServiceSavesJwtSigningPublicKeyWithKeyId() throws Exception {
        Path aclDirectory = tempDir.resolve("managed-token-user-acl");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(localConfig(aclDirectory));
        OrionAccessControlServiceImpl service = startAccessControlService(storage);

        service.createOrUpdateUser(new AccessControlUserUpdate(
                "token-client",
                "token-client@example.test",
                List.of(new AccessControlCredentialUpdate(
                        AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY,
                        JWT_SIGNING_KEY_ID,
                        JWT_SIGNING_PUBLIC_KEY)),
                List.of()));

        AccessControl savedAccessControl = accessControlFrom(storage.load().valueOrFailure("ACL should be saved"));
        AccessControl.Credential credential = credentialByType(
                userById(savedAccessControl, "token-client"),
                AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY);
        assertThat(credential.getKeyId()).isEqualTo(JWT_SIGNING_KEY_ID);
        assertThat(credential.getValue()).isEqualTo(JWT_SIGNING_PUBLIC_KEY);
    }

    @Test
    void accessControlServiceRejectsJwtSigningPublicKeyWithoutKeyId() throws Exception {
        Path aclDirectory = tempDir.resolve("managed-token-user-without-key-id-acl");
        OrionAccessControlServiceImpl service = startAccessControlService(new LocalAccessControlStorage(localConfig(aclDirectory)));

        assertThatThrownBy(() -> service.createOrUpdateUser(new AccessControlUserUpdate(
                "token-client",
                "token-client@example.test",
                List.of(new AccessControlCredentialUpdate(
                        AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY,
                        JWT_SIGNING_PUBLIC_KEY)),
                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT signing key id is required");
    }

    private OrionConfiguration.BootstrapAccessControlConfig localConfig(Path directory) {
        OrionConfiguration.BootstrapAccessControlConfig config = new OrionConfiguration.BootstrapAccessControlConfig();
        config.setLocation(directory.toUri().toString());
        config.setPaths(List.of(ACL_FILE));
        return config;
    }

    private OrionConfiguration.BootstrapAccessControlConfig localGitConfig(Path repositoryPath) {
        OrionConfiguration.BootstrapAccessControlConfig config = new OrionConfiguration.BootstrapAccessControlConfig();
        config.setLocation(repositoryPath.toUri().toString());
        config.setBranch(BRANCH);
        config.setPaths(List.of(ACL_FILE));
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

    private byte[] aclBytesWithPasswordUser(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControlWithPasswordUser(userId), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        AccessControl accessControl = new AccessControl();
        accessControl.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test"));
        return accessControl;
    }

    private AccessControl accessControlWithPasswordUser(String userId) {
        AccessControl accessControl = new AccessControl();
        accessControl.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, DEFAULT_ROOT_PASSWORD_HASH));
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
        return startAccessControlService(storage, testConfiguration());
    }

    private OrionAccessControlServiceImpl startAccessControlService(
            AccessControlStorage storage,
            OrionConfiguration configuration) throws Exception {
        return startAccessControlService(storage, configuration, PublicKeysProvider.DEFAULT);
    }

    private OrionAccessControlServiceImpl startAccessControlService(
            AccessControlStorage storage,
            OrionConfiguration configuration,
            PublicKeysProvider publicKeysProvider) throws Exception {
        ServerKeySigner serverKeySigner = serverKeySignerFor(publicKeysProvider);
        TestProviderContext providerContext = startedProviderContext();
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(
                storage,
                new FixedPasswordHashingService(),
                providerContext.orionProvider(),
                configuration,
                publicKeysProvider,
                serverKeySigner) {
            @Override
            protected PasswordHashingAlgorithm defaultPasswordHashingAlgorithm() {
                return SHA1;
            }
        };
        startWithoutRootPasswordOutput(service);
        return service;
    }

    private ServerKeySigner serverKeySignerFor(PublicKeysProvider publicKeysProvider) {
        if (publicKeysProvider instanceof ServerKeySigner provider) {
            return provider;
        }
        return ServerKeySigner.DEFAULT;
    }

    private OrionConfiguration testConfiguration() {
        return new OrionConfiguration();
    }

    private TestProviderContext startedProviderContext() {
        TestProviderContext providerContext = new TestProviderContext();
        providerContexts.add(providerContext);
        return providerContext;
    }

    private void startWithoutRootPasswordOutput(OrionAccessControlServiceImpl service) throws Exception {
        synchronized (AccessControlStorageTest.class) {
            PrintStream originalOut = System.out;
            try {
                System.setOut(new PrintStream(OutputStream.nullOutputStream()));
                OrionStageCallResult result = service.aclLoad();
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
                .hasSize(1)
                .containsEntry(AccessControl.CredentialType.SHA1, DEFAULT_ROOT_PASSWORD_HASH);

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
                DEFAULT_ROOT_PASSWORD.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success success = (AuthenticationResult.Success) result;
        assertThat(success.userIdentity().getUserId()).isEqualTo("root");
        assertThat(grantIds(success.userIdentity().getGrants())).containsExactly(
                "CONNECT",
                "ALL_REPOSITORY",
                "APPLICATION_CONTROL");
    }

    private void assertUserAuthenticates(OrionAccessControlServiceImpl service, String userId) {
        AuthenticationResult result = service.authenticateUser(
                userId,
                DEFAULT_ROOT_PASSWORD.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isInstanceOf(AuthenticationResult.Success.class);
        AuthenticationResult.Success success = (AuthenticationResult.Success) result;
        assertThat(success.userIdentity().getUserId()).isEqualTo(userId);
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

    private AccessControl.User userById(AccessControl accessControl, String userId) {
        for (AccessControl.User user : accessControl.getUsers()) {
            if (userId.equals(user.getId())) {
                return user;
            }
        }
        throw new IllegalStateException("Missing user " + userId);
    }

    private AccessControl.Credential credentialByType(AccessControl.User user, AccessControl.CredentialType type) {
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential.getType() == type) {
                return credential;
            }
        }
        throw new IllegalStateException("Missing credential " + type);
    }

    private boolean hasPublicKeyCredential(AccessControl.User user, PublicKey publicKey) {
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential.getType() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY
                    && Arrays.equals(KeyUtils.readPublicKeyFromString(credential.getValue()).getEncoded(), publicKey.getEncoded())) {
                return true;
            }
        }
        return false;
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

    private static final class TestProviderContext implements AutoCloseable {
        private final OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
        private final OrionEventManager eventManager = new OrionEventManager();
        private final OrionProvider provider = new OrionProvider(
                new ApplicationStateHolder(),
                () -> null,
                () -> eventManager,
                () -> executor);

        private TestProviderContext() {
            eventManager.onInit();
        }

        private OrionEventManager eventManager() {
            return eventManager;
        }

        private OrionProvider orionProvider() {
            return provider;
        }

        @Override
        public void close() {
            eventManager.onStop();
            executor.onStop();
        }
    }

    private static final class FixedPasswordHashingService extends OrionPasswordHashingService {
        @Override
        public char[] generateRandomString(int length) {
            return DEFAULT_ROOT_PASSWORD.toCharArray();
        }
    }

    private record SeededRepository(Path repositoryPath, String commitId) {
    }
}
