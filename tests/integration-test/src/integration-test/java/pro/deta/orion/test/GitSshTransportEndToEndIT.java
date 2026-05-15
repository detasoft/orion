package pro.deta.orion.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.FileGitRepositoryProvider;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.util.FileUtils;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.NetworkUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitSshTransportEndToEndIT {
    private static final String BRANCH = "master";
    private static final String USERNAME = "e2e";
    private static final KeyPair TRUSTED_USER_KEY = loadTestRsaKeyPair("e2e/trusted-user-rsa.pem");
    private static final KeyPair UNKNOWN_USER_KEY = loadTestRsaKeyPair("e2e/unknown-user-rsa.pem");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private StartedOrion startedOrion;

    @AfterEach
    void stopServer() {
        if (startedOrion != null) {
            startedOrion.stop();
        }
    }

    @Test
    void authorizedUserCanPushCloneAndPullRepositoryOverSsh() throws Exception {
        /*
         * This is the primary end-to-end Git transport scenario.
         *
         * 1. Load a pregenerated SSH private key from test resources so the scenario does not spend time on RSA key generation.
         * 2. Start a real Orion runtime with pregenerated server host keys and a pre-seeded ACL repository that trusts
         *    that public key.
         * 3. Create a normal local Git repository with one commit.
         * 4. Push that commit to Orion through the SSH Git transport. This exercises SSH authentication,
         *    SshCommandFactory, GitInternalService, permission checks, repository creation, and JGit receive-pack.
         * 5. Clone the repository back through the same SSH transport and verify the checked-out file content.
         * 6. Push another commit from the source repository, pull it in the clone, and verify the clone sees the update.
         */
        startedOrion = startOrion(tempDir.resolve("orion-root"), TRUSTED_USER_KEY);

        Path sourceDirectory = tempDir.resolve("source");
        Path cloneDirectory = tempDir.resolve("clone");
        String remoteUrl = startedOrion.sshUrl("project.git");

        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("ssh-home"), TRUSTED_USER_KEY);
             Git source = initRepository(sourceDirectory)) {
            assertThat(startedOrion.repositoryPath("project")).doesNotExist();

            ObjectId initialCommit = createCommit(source, "README.md", "hello from e2e\n", "initial commit");

            Iterable<PushResult> pushResults = source.push()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();

            assertThat(pushResults)
                    .flatExtracting(PushResult::getRemoteUpdates)
                    .extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
            assertRepositoryContains("project", initialCommit, "README.md", "hello from e2e\n");

            try (Git clone = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(cloneDirectory.toFile())
                    .setBranch(BRANCH)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .call()) {
                assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("hello from e2e\n");

                ObjectId updatedCommit = createCommit(source, "README.md", "hello after pull\n", "update readme");
                source.push()
                        .setRemote(remoteUrl)
                        .setTransportConfigCallback(sshCallback(ssh))
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();
                assertRepositoryContains("project", updatedCommit, "README.md", "hello after pull\n");

                clone.pull()
                        .setRemote("origin")
                        .setRemoteBranchName(BRANCH)
                        .setTransportConfigCallback(sshCallback(ssh))
                        .call();
            }
        }

        assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("hello after pull\n");
        assertThat(startedOrion.repositoryPath("project").resolve("config")).exists();
        assertThat(startedOrion.repositoryPath("orion").resolve("config")).exists();
    }

    @Test
    void authorizedUserCanCreateRepositoryPushCommitAndFetchItOverSsh() throws Exception {
        startedOrion = startOrion(tempDir.resolve("orion-root"), TRUSTED_USER_KEY);

        Path sourceDirectory = tempDir.resolve("push-source");
        Path fetchDirectory = tempDir.resolve("fetch-target");
        String repositoryName = "fetch-project";
        String remoteUrl = startedOrion.sshUrl(repositoryName + ".git");

        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("ssh-home"), TRUSTED_USER_KEY);
             Git source = initRepository(sourceDirectory);
             Git fetchTarget = initRepository(fetchDirectory)) {
            assertThat(startedOrion.repositoryPath(repositoryName)).doesNotExist();

            ObjectId initialCommit = createCommit(source, "README.md", "created through full server e2e\n", "initial commit");

            Iterable<PushResult> pushResults = source.push()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();

            assertThat(pushResults)
                    .flatExtracting(PushResult::getRemoteUpdates)
                    .extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
            assertThat(startedOrion.repositoryPath(repositoryName).resolve("config")).exists();
            assertRepositoryContains(repositoryName, initialCommit, "README.md", "created through full server e2e\n");

            fetchTarget.fetch()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH))
                    .call();

            fetchTarget.checkout()
                    .setCreateBranch(true)
                    .setName(BRANCH)
                    .setStartPoint("refs/remotes/origin/" + BRANCH)
                    .call();
        }

        assertThat(Files.readString(fetchDirectory.resolve("README.md"))).isEqualTo("created through full server e2e\n");
    }

    @Test
    void unknownSshKeyCannotCreateRepositoryOverSsh() throws Exception {
        /*
         * This is the negative end-to-end transport scenario.
         *
         * 1. Start Orion with an ACL that trusts one pregenerated SSH public key.
         * 2. Try to access a new repository with a different SSH private key.
         * 3. Assert that the JGit client gets a transport authentication failure.
         * 4. Assert that the denied request did not create a repository on disk as a side effect.
         */
        startedOrion = startOrion(tempDir.resolve("orion-root"), TRUSTED_USER_KEY);

        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("ssh-home"), UNKNOWN_USER_KEY)) {
            assertThatThrownBy(() -> Git.lsRemoteRepository()
                    .setRemote(startedOrion.sshUrl("denied.git"))
                    .setTransportConfigCallback(sshCallback(ssh))
                    .call())
                    .isInstanceOf(TransportException.class);
        }

        assertThat(startedOrion.repositoryPath("denied")).doesNotExist();
    }

    @Test
    void managedUserCanPushAndCloneRepositoryAfterServerRestart() throws Exception {
        /*
         * This scenario starts from a clean local runtime configuration rather than pre-seeding ACL fixtures:
         * the server creates the default local ACL repository, a client generates its own SSH key, and the admin
         * HTTP API grants that key repository access. The pushed Git data must survive a server restart with the
         * same local configuration.
         */
        Path orionRoot = tempDir.resolve("orion-root");
        Path cloneDirectory = tempDir.resolve("managed-clone");
        String repositoryName = "managed-project";
        KeyPair clientKey = KeyUtils.generateRSAKeyPair().valueOrFailure("Client SSH key should be generated");

        startedOrion = startFreshOrion(orionRoot);
        String rootToken = TestBearerTokens.issueRootToken(
                startedOrion.accessControlService(),
                startedOrion.httpUrl("/api/admin/token"),
                3_600);
        createManagedUser(startedOrion, rootToken, clientKey, repositoryName);
        createManagedRepository(startedOrion, rootToken, repositoryName);

        String remoteUrl = startedOrion.sshUrl(repositoryName + ".git");
        ObjectId pushedCommit;
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("ssh-home"), clientKey);
             Git clone = Git.cloneRepository()
                     .setURI(remoteUrl)
                     .setDirectory(cloneDirectory.toFile())
                     .setTransportConfigCallback(sshCallback(ssh))
                     .call()) {
            pushedCommit = createCommit(clone, "state.txt", "survived restart\n", "persist state");
            Iterable<PushResult> pushResults = clone.push()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();

            assertThat(pushResults)
                    .flatExtracting(PushResult::getRemoteUpdates)
                    .extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
        }
        assertRepositoryContains(repositoryName, pushedCommit, "state.txt", "survived restart\n");

        startedOrion.stop();
        startedOrion = null;
        startedOrion = startExistingOrion(orionRoot);

        FileUtils.wipeDirectory(cloneDirectory);
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("ssh-home-after-restart"), clientKey);
             Git ignored = Git.cloneRepository()
                     .setURI(startedOrion.sshUrl(repositoryName + ".git"))
                     .setDirectory(cloneDirectory.toFile())
                     .setBranch(BRANCH)
                     .setTransportConfigCallback(sshCallback(ssh))
                     .call()) {
            assertThat(Files.readString(cloneDirectory.resolve("state.txt"))).isEqualTo("survived restart\n");
        }
    }

    @Test
    void rootCanIssueBearerTokenOverSshWithServerIdentityKey() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);

        KeyPair serverIdentityKey = KeyUtils.readKeyFromFile(orionRoot.resolve("server-identity").resolve("signing-rsa.pem"))
                .valueOrFailure("Server identity key should be available after startup");

        String token = issueTokenOverSsh(startedOrion, serverIdentityKey, 600);

        assertThat(token).isNotBlank();
        assertThat(startedOrion.accessControlService().authenticateToken(token.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(pro.deta.orion.auth.AuthenticationResult.Success.class);
    }

    @Test
    void shutdownCommandStopsServerPromptlyOverSsh() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);

        KeyPair serverIdentityKey = KeyUtils.readKeyFromFile(orionRoot.resolve("server-identity").resolve("signing-rsa.pem"))
                .valueOrFailure("Server identity key should be available after startup");

        long startedAtNanos = System.nanoTime();
        int exitStatus = executeShutdownOverSsh(startedOrion, serverIdentityKey);
        startedOrion.lifecycle().waitForShutdown();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

        assertThat(exitStatus).isEqualTo(0);
        assertThat(elapsedMillis).isLessThan(3_000);
        startedOrion = null;
    }

    @Test
    void componentCanRestartAndServeSshGitOperationsInSameJvm() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        FileUtils.wipeDirectory(orionRoot);
        seedServerKeys(orionRoot);
        seedAclRepository(orionRoot, TRUSTED_USER_KEY);

        startedOrion = startExistingOrion(orionRoot);
        startedOrion.stopSynchronously();
        startedOrion = null;

        startedOrion = startExistingOrion(orionRoot);
        pushCloneAndFetchThroughSsh(
                startedOrion,
                "project",
                tempDir.resolve("component-restart"),
                "after component restart\n",
                "after component restart fetch\n");
    }

    @Test
    void preseededAclSshScenarioCanRunTwiceInSameJvm() throws Exception {
        startedOrion = startOrion(tempDir.resolve("first-orion-root"), TRUSTED_USER_KEY);
        pushCloneAndFetchThroughSsh(
                startedOrion,
                "project",
                tempDir.resolve("first-e2e"),
                "first scenario\n",
                "first scenario fetch\n");
        startedOrion.stop();
        startedOrion = null;

        startedOrion = startOrion(tempDir.resolve("second-orion-root"), TRUSTED_USER_KEY);
        pushCloneAndFetchThroughSsh(
                startedOrion,
                "project",
                tempDir.resolve("second-e2e"),
                "second scenario\n",
                "second scenario fetch\n");
    }

    private StartedOrion startOrion(Path orionRoot, KeyPair userKey) throws Exception {
        /*
         * The application normally creates fresh server host keys and a default root user on first startup.
         * For an automated SSH E2E test we need deterministic test fixtures instead, so the test seeds the
         * server keys and creates the ACL Git repository before boot with an ACL document that trusts userKey.
         */
        FileUtils.wipeDirectory(orionRoot);
        seedServerKeys(orionRoot);
        seedAclRepository(orionRoot, userKey);
        return startOrion(e2eConfiguration(orionRoot));
    }

    private StartedOrion startFreshOrion(Path orionRoot) throws Exception {
        FileUtils.wipeDirectory(orionRoot);
        seedServerKeys(orionRoot);
        return startExistingOrion(orionRoot);
    }

    private StartedOrion startExistingOrion(Path orionRoot) throws Exception {
        return startOrion(e2eConfiguration(orionRoot));
    }

    private StartedOrion startOrion(OrionConfiguration configuration) {
        OrionComponent component = DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .build();
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        assertThat(lifecycle.runApplication()).isEqualTo(ApplicationState.UP);
        lifecycle.waitForStarting();

        return new StartedOrion(configuration, lifecycle, component.gitRepositoryProvider(), component.orionAccessControlService());
    }

    private static void createManagedUser(StartedOrion orion, String rootToken, KeyPair clientKey, String repositoryName) throws Exception {
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "id", USERNAME,
                "email", "e2e@example.test",
                "publicKey", KeyUtils.publicKeyToString(clientKey.getPublic()),
                "repositories", List.of(Map.of(
                        "repository", repositoryName,
                        "read", true,
                        "write", true,
                        "create", true,
                        "branch", "*"))));
        postAdmin(orion, rootToken, "/api/admin/users", payload);
    }

    private static void createManagedRepository(StartedOrion orion, String rootToken, String repositoryName) throws Exception {
        postAdmin(orion, rootToken, "/api/admin/repositories",
                OBJECT_MAPPER.writeValueAsString(Map.of("name", repositoryName)));
    }

    private static void postAdmin(StartedOrion orion, String rootToken, String path, String payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) orion.httpUrl(path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", TestBearerTokens.bearer(rootToken));
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (var output = connection.getOutputStream()) {
            output.write(body);
        }

        assertThat(connection.getResponseCode())
                .as("admin POST %s", path)
                .isEqualTo(HttpURLConnection.HTTP_CREATED);
    }

    private static String issueTokenOverSsh(StartedOrion orion, KeyPair keyPair, long expiresInSeconds) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        client.start();
        try (ClientSession session = client.connect(
                        "root",
                        orion.configuration().getTransport().getSsh().getAddress(),
                        orion.configuration().getTransport().getSsh().getPort())
                .verify(10, TimeUnit.SECONDS)
                .getSession()) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(10, TimeUnit.SECONDS);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            try (ClientChannel channel = session.createExecChannel("issue-token " + expiresInSeconds)) {
                channel.setOut(output);
                channel.setErr(error);
                channel.open().verify(10, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(10));

                assertThat(channel.getExitStatus())
                        .as("issue-token stderr: %s", new String(error.toByteArray(), StandardCharsets.UTF_8))
                        .isEqualTo(0);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            client.stop();
        }
    }

    private static int executeShutdownOverSsh(StartedOrion orion, KeyPair keyPair) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        client.start();
        try (ClientSession session = client.connect(
                        "root",
                        orion.configuration().getTransport().getSsh().getAddress(),
                        orion.configuration().getTransport().getSsh().getPort())
                .verify(10, TimeUnit.SECONDS)
                .getSession()) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(10, TimeUnit.SECONDS);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            try (ClientChannel channel = session.createExecChannel("shutdown")) {
                channel.setOut(output);
                channel.setErr(error);
                channel.open().verify(10, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(10));

                assertThat(channel.getExitStatus())
                        .as("shutdown stderr: %s", new String(error.toByteArray(), StandardCharsets.UTF_8))
                        .isNotNull();
                return channel.getExitStatus();
            }
        } finally {
            client.stop();
        }
    }

    private static OrionConfiguration e2eConfiguration(Path orionRoot) throws Exception {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(orionRoot.toString());
        configuration.getBootstrap().setThreadPoolSize(8);
        configuration.getStorage().setLocation(orionRoot.resolve("repos").toUri().toString());

        configuration.getBootstrap().getAccessControl().setLocation("local:orion");

        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getGit().setAddress("localhost");
        configuration.getTransport().getGit().setPort(NetworkUtils.findAvailablePort());

        configuration.getTransport().getSsh().setEnabled(true);
        configuration.getTransport().getSsh().setAddress("localhost");
        configuration.getTransport().getSsh().setPort(NetworkUtils.findAvailablePort());

        configuration.getTransport().getHttp().setAddress("localhost");
        configuration.getTransport().getHttp().setPort(NetworkUtils.findAvailablePort());

        configuration.getTransport().getHttps().setEnabled(false);
        configuration.getTransport().getHttps().setAddress("localhost");
        configuration.getTransport().getHttps().setPort(NetworkUtils.findAvailablePort());
        return configuration;
    }

    private void assertRepositoryContains(String repositoryName, ObjectId commitId, String fileName, String expectedContent) throws Exception {
        assertRepositoryContains(startedOrion, repositoryName, commitId, fileName, expectedContent);
    }

    private static void assertRepositoryContains(StartedOrion orion, String repositoryName, ObjectId commitId,
                                                 String fileName, String expectedContent) throws Exception {
        try (Repository repository = FileRepositoryBuilder.create(orion.repositoryPath(repositoryName).toFile())) {
            assertThat(repository.exactRef("refs/heads/" + BRANCH).getObjectId())
                    .as("server %s ref", repositoryName)
                    .isEqualTo(commitId);
            assertThat(repository.getObjectDatabase().has(commitId))
                    .as("server %s object database contains %s", repositoryName, commitId.name())
                    .isTrue();
            assertThat(readFileFromCommit(repository, commitId, fileName)).isEqualTo(expectedContent);
        }
    }

    private static void pushCloneAndFetchThroughSsh(StartedOrion orion, String repositoryName, Path root,
                                                    String initialContent, String updatedContent) throws Exception {
        Path sourceDirectory = root.resolve("source");
        Path cloneDirectory = root.resolve("clone");
        String remoteUrl = orion.sshUrl(repositoryName + ".git");

        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(root.resolve("ssh-home"), TRUSTED_USER_KEY);
             Git source = initRepository(sourceDirectory)) {
            ObjectId initialCommit = createCommit(source, "state.txt", initialContent, "initial e2e state");
            Iterable<PushResult> initialPushResults = source.push()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();

            assertThat(initialPushResults)
                    .flatExtracting(PushResult::getRemoteUpdates)
                    .extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
            assertRepositoryContains(orion, repositoryName, initialCommit, "state.txt", initialContent);

            try (Git clone = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(cloneDirectory.toFile())
                    .setBranch(BRANCH)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .call()) {
                assertThat(Files.readString(cloneDirectory.resolve("state.txt"))).isEqualTo(initialContent);

                ObjectId updatedCommit = createCommit(source, "state.txt", updatedContent, "updated e2e state");
                Iterable<PushResult> updatedPushResults = source.push()
                        .setRemote(remoteUrl)
                        .setTransportConfigCallback(sshCallback(ssh))
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();

                assertThat(updatedPushResults)
                        .flatExtracting(PushResult::getRemoteUpdates)
                        .extracting(RemoteRefUpdate::getStatus)
                        .containsExactly(RemoteRefUpdate.Status.OK);
                assertRepositoryContains(orion, repositoryName, updatedCommit, "state.txt", updatedContent);

                clone.fetch()
                        .setRemote("origin")
                        .setTransportConfigCallback(sshCallback(ssh))
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH))
                        .call();

                assertThat(clone.getRepository().resolve("refs/remotes/origin/" + BRANCH)).isEqualTo(updatedCommit);
            }
        }
    }

    private static String readFileFromCommit(Repository repository, ObjectId commitId, String fileName) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, fileName, commit.getTree())) {
                assertThat(treeWalk)
                        .as("server repository tree entry %s at %s", fileName, commitId.name())
                        .isNotNull();
                try (var reader = repository.newObjectReader()) {
                    return new String(reader.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void seedServerKeys(Path orionRoot) throws IOException {
        /*
         * SshHostKeyService generates host keys when baseDir/ssh-host-keys is empty. The E2E test uses a fresh baseDir
         * for every scenario, so pregenerated test-only keys avoid paying RSA/ECDSA generation cost on every boot.
         */
        Path serverKeysDirectory = orionRoot.resolve("ssh-host-keys");
        Files.createDirectories(serverKeysDirectory);
        copyTestResource("e2e/server-rsa.pem", serverKeysDirectory.resolve("rsa.pem"));
        copyTestResource("e2e/server-ecdsa.pem", serverKeysDirectory.resolve("ecdsa.pem"));
    }

    private static void seedAclRepository(Path orionRoot, KeyPair userKey) throws Exception {
        /*
         * ACL storage reads ACL from a normal Orion repository named "orion".
         * This helper creates that bare repository, writes orion.xml in a temporary worktree, commits it,
         * and pushes master into the bare repo. Orion then loads the ACL through the same storage path it
         * uses in production startup.
         */
        Path bareAclRepository = orionRoot.resolve("repos").resolve("orion");
        Files.createDirectories(bareAclRepository);

        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(bareAclRepository.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            // Repository is intentionally created empty before seeding it from a normal worktree.
        }

        Path seedWorktree = orionRoot.resolve("acl-seed-worktree");
        try (Git seed = initRepository(seedWorktree)) {
            try (var output = Files.newOutputStream(seedWorktree.resolve("orion.xml"))) {
                new XmlService().serialize(accessControlFor(userKey.getPublic()), output);
            }
            seed.add().addFilepattern("orion.xml").call();
            seed.commit()
                    .setAuthor("E2E Test", "e2e@example.test")
                    .setCommitter("E2E Test", "e2e@example.test")
                    .setMessage("seed e2e access control")
                    .call();
            seed.push()
                    .setRemote(bareAclRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
        }
    }

    private static AccessControl accessControlFor(PublicKey userPublicKey) {
        /*
         * The test user intentionally gets broad repository permissions. The E2E test is focused on verifying
         * the full SSH transport path and repository lifecycle, not fine-grained ACL matching; narrower ACL
         * behavior is covered by unit tests around access rules.
         */
        AccessControlDraft draft = new AccessControlDraft();
        AccessControlDraft.User user = ACLUtil.createUser(USERNAME, "e2e@example.test")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, KeyUtils.publicKeyToString(userPublicKey));
        allowRepository(user, "project");
        allowRepository(user, "fetch-project");
        draft.getUsers().add(user);
        return draft.toAccessControl();
    }

    private static void allowRepository(AccessControlDraft.User user, String repositoryName) {
        user.addGrant("REPOSITORY_" + repositoryName)
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "*");
    }

    private static KeyPair loadTestRsaKeyPair(String resourceName) {
        URL resource = testResourceUrl(resourceName);
        try {
            return KeyUtils.readRSAKeyPair(Path.of(resource.toURI()))
                    .valueOrFailure("Cannot read test SSH key " + resourceName);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load test SSH key resource: " + resourceName, e);
        }
    }

    private static URL testResourceUrl(String resourceName) {
        URL resource = GitSshTransportEndToEndIT.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new IllegalStateException("Missing test resource: " + resourceName);
        }
        return resource;
    }

    private static void copyTestResource(String resourceName, Path target) throws IOException {
        try (InputStream input = GitSshTransportEndToEndIT.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + resourceName);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Git initRepository(Path directory) throws Exception {
        Files.createDirectories(directory);
        Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch(BRANCH)
                .call();
        git.getRepository().getConfig().setString("user", null, "name", "E2E Test");
        git.getRepository().getConfig().setString("user", null, "email", "e2e@example.test");
        git.getRepository().getConfig().save();
        return git;
    }

    private static ObjectId createCommit(Git git, String fileName, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(fileName), content);
        git.add().addFilepattern(fileName).call();
        return git.commit()
                .setAuthor("E2E Test", "e2e@example.test")
                .setCommitter("E2E Test", "e2e@example.test")
                .setMessage(message + " " + Instant.now())
                .call()
                .toObjectId();
    }

    private static SshdSessionFactory acceptingPublicKeySshFactory(Path home, KeyPair keyPair) throws Exception {
        /*
         * This client-side factory forces public-key authentication and injects the pregenerated test key.
         * Host-key verification is relaxed here because the server host key is a local test fixture; the behavior
         * under test is Orion's SSH/Git/auth pipeline, not known_hosts persistence.
         */
        Path sshConfigDir = home.resolve(".ssh");
        Files.createDirectories(sshConfigDir);
        return new SshdSessionFactoryBuilder()
                .setHomeDirectory(home.toFile())
                .setSshDirectory(sshConfigDir.toFile())
                .setPreferredAuthentications("publickey")
                .setDefaultKeysProvider(sshDir -> List.of(keyPair))
                .setServerKeyDatabase((homeDir, sshDir) -> new ServerKeyDatabase() {
                    @Override
                    public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress, Configuration config) {
                        return List.of();
                    }

                    @Override
                    public boolean accept(String connectAddress, InetSocketAddress remoteAddress, PublicKey serverKey,
                                          Configuration config, org.eclipse.jgit.transport.CredentialsProvider provider) {
                        return true;
                    }
                })
                .build(null);
    }

    private static TransportConfigCallback sshCallback(SshdSessionFactory ssh) {
        return transport -> ((SshTransport) transport).setSshSessionFactory(ssh);
    }

    private record StartedOrion(OrionConfiguration configuration, OrionApplicationLifecycle lifecycle,
                                GitRepositoryProvider gitRepositoryProvider,
                                OrionAccessControlServiceImpl accessControlService) {
        private String sshUrl(String repository) {
            return "ssh://%s@%s:%d/%s".formatted(
                    USERNAME,
                    configuration.getTransport().getSsh().getAddress(),
                    configuration.getTransport().getSsh().getPort(),
                    repository);
        }

        private Path repositoryPath(String repository) {
            return ((FileGitRepositoryProvider) gitRepositoryProvider).repositoryPathForTests(repository);
        }

        private URL httpUrl(String path) throws IOException {
            return new URL(
                    "http",
                    configuration.getTransport().getHttp().getAddress(),
                    configuration.getTransport().getHttp().getPort(),
                    path);
        }

        private void stopSynchronously() {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(ApplicationState.OFF);
        }

        private void stop() {
            lifecycle.beginShutdown();
            lifecycle.waitForShutdown();
        }
    }
}
