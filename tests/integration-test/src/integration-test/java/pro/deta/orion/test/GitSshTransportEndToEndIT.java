package pro.deta.orion.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
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
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.util.FileUtils;
import pro.deta.orion.util.KeyUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

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
         *    SshCommandFactory, permission checks, repository creation, and receive-pack handling.
         * 5. Clone the repository back through the same SSH transport and verify the checked-out file content.
         * 6. Push another commit from the source repository, pull it in the clone, and verify the clone sees the update.
         */
        startedOrion = startOrion(tempDir.resolve("orion-root"), TRUSTED_USER_KEY);

        Path sourceDirectory = tempDir.resolve("source");
        Path cloneDirectory = tempDir.resolve("clone");
        String remoteUrl = startedOrion.sshUrl("project.git");

        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("ssh-home"), TRUSTED_USER_KEY);
             Git source = initRepository(sourceDirectory)) {
            assertThat(startedOrion.repositoryExists("project")).isFalse();

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
        assertThat(startedOrion.repositoryExists("project")).isTrue();
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
            assertThat(startedOrion.repositoryExists(repositoryName)).isFalse();

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
            assertThat(startedOrion.repositoryExists(repositoryName)).isTrue();
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
         * 3. Assert that the Git client gets a transport authentication failure.
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

        assertThat(startedOrion.repositoryExists("denied")).isFalse();
    }

    @Test
    void authenticatedReadOnlyUserCanCloneButCannotPushOverSsh() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        String repositoryName = "read-only-project";
        FileUtils.wipeDirectory(orionRoot);
        seedServerKeys(orionRoot);
        seedAclRepository(orionRoot, accessControlForReadOnlyRepository(TRUSTED_USER_KEY.getPublic(), repositoryName));
        seedProjectRepository(orionRoot, repositoryName, "read-only seed\n");
        startedOrion = startExistingOrion(orionRoot);

        Path cloneDirectory = tempDir.resolve("read-only-clone");
        String remoteUrl = startedOrion.sshUrl(repositoryName + ".git");
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("read-only-ssh-home"), TRUSTED_USER_KEY);
             Git clone = Git.cloneRepository()
                     .setURI(remoteUrl)
                     .setDirectory(cloneDirectory.toFile())
                     .setBranch(BRANCH)
                     .setTransportConfigCallback(sshCallback(ssh))
                     .call()) {
            assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("read-only seed\n");

            createCommit(clone, "README.md", "read-only update\n", "try read-only update");
            assertThatThrownBy(() -> clone.push()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call())
                    .isInstanceOf(TransportException.class);
        }

        assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("read-only update\n");
        assertThat(startedOrion.repositoryExists(repositoryName)).isTrue();
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
        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();
        String rootToken = issueTokenOverSsh(startedOrion, serverIdentityKey, 3_600);
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
    void managedUserUpdateReplacesSshKeyAndRepositoryGrants() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        String oldRepositoryName = "managed-old-project";
        String newRepositoryName = "managed-new-project";

        startedOrion = startFreshOrion(orionRoot);
        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();
        String rootToken = issueTokenOverSsh(startedOrion, serverIdentityKey, 3_600);

        createManagedUser(startedOrion, rootToken, TRUSTED_USER_KEY, oldRepositoryName);
        createManagedRepository(startedOrion, rootToken, oldRepositoryName);
        ObjectId oldCommit = pushCommitThroughSsh(
                startedOrion,
                TRUSTED_USER_KEY,
                oldRepositoryName,
                tempDir.resolve("managed-old-source"),
                tempDir.resolve("managed-old-ssh-home"),
                "old access\n");
        assertRepositoryContains(oldRepositoryName, oldCommit, "state.txt", "old access\n");

        createManagedUser(startedOrion, rootToken, UNKNOWN_USER_KEY, newRepositoryName);
        createManagedRepository(startedOrion, rootToken, newRepositoryName);

        assertSshRemoteDenied(startedOrion, TRUSTED_USER_KEY, oldRepositoryName, tempDir.resolve("old-key-after-update-ssh-home"));
        assertSshRemoteDenied(startedOrion, UNKNOWN_USER_KEY, oldRepositoryName, tempDir.resolve("new-key-old-repository-ssh-home"));

        ObjectId newCommit = pushCommitThroughSsh(
                startedOrion,
                UNKNOWN_USER_KEY,
                newRepositoryName,
                tempDir.resolve("managed-new-source"),
                tempDir.resolve("managed-new-ssh-home"),
                "new access\n");
        assertRepositoryContains(newRepositoryName, newCommit, "state.txt", "new access\n");

        startedOrion.stop();
        startedOrion = null;
        startedOrion = startExistingOrion(orionRoot);

        assertSshRemoteDenied(startedOrion, TRUSTED_USER_KEY, newRepositoryName, tempDir.resolve("old-key-after-restart-ssh-home"));
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("new-key-after-restart-ssh-home"), UNKNOWN_USER_KEY);
             Git ignored = Git.cloneRepository()
                     .setURI(startedOrion.sshUrl(newRepositoryName + ".git"))
                     .setDirectory(tempDir.resolve("managed-new-clone-after-restart").toFile())
                     .setBranch(BRANCH)
                     .setTransportConfigCallback(sshCallback(ssh))
                     .call()) {
            assertThat(Files.readString(tempDir.resolve("managed-new-clone-after-restart").resolve("state.txt")))
                    .isEqualTo("new access\n");
        }
        assertRepositoryContains(newRepositoryName, newCommit, "state.txt", "new access\n");
    }

    @Test
    void managedUserCanCreateOwnRepositoryByFirstPushOverSsh() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        Path sourceDirectory = tempDir.resolve("self-service-source");
        Path cloneDirectory = tempDir.resolve("self-service-clone");
        String repositoryName = USERNAME;
        KeyPair clientKey = KeyUtils.generateRSAKeyPair().valueOrFailure("Client SSH key should be generated");

        startedOrion = startFreshOrion(orionRoot);
        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();
        String rootToken = issueTokenOverSsh(startedOrion, serverIdentityKey, 3_600);
        createManagedUser(startedOrion, rootToken, clientKey, repositoryName);

        String remoteUrl = startedOrion.sshUrl(repositoryName + ".git");
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(tempDir.resolve("self-service-ssh-home"), clientKey);
             Git source = initRepository(sourceDirectory)) {
            assertThat(startedOrion.repositoryExists(repositoryName)).isFalse();

            ObjectId initialCommit = createCommit(source, "README.md", "created by " + USERNAME + "\n", "self-service create");
            Iterable<PushResult> pushResults = source.push()
                    .setRemote(remoteUrl)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();

            assertThat(pushResults)
                    .flatExtracting(PushResult::getRemoteUpdates)
                    .extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
            assertRepositoryContains(repositoryName, initialCommit, "README.md", "created by " + USERNAME + "\n");

            try (Git ignored = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(cloneDirectory.toFile())
                    .setBranch(BRANCH)
                    .setTransportConfigCallback(sshCallback(ssh))
                    .call()) {
                assertThat(Files.readString(cloneDirectory.resolve("README.md")))
                        .isEqualTo("created by " + USERNAME + "\n");
            }
        }
    }

    @Test
    void rootCanIssueBearerTokenOverSshWithServerIdentityKey() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);

        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();

        String token = issueTokenOverSsh(startedOrion, serverIdentityKey, 600);

        assertThat(token).isNotBlank();
        assertThat(startedOrion.accessControlService().authenticateToken(token.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(pro.deta.orion.auth.AuthenticationResult.Success.class);
        HttpResponse response = getAdminAclWithBearerToken(startedOrion, token);
        assertThat(response.status()).isEqualTo(HttpURLConnection.HTTP_OK);
        assertThat(response.contentType()).startsWith("application/xml");
    }

    @Test
    void rootCanListRepositoriesOverSsh() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);
        startedOrion.gitRepositoryProvider().create("zeta").valueOrFailure("create zeta");
        startedOrion.gitRepositoryProvider().create("team/repository").valueOrFailure("create team repository");
        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();

        String repositories = executeRootCommandOverSsh(startedOrion, serverIdentityKey, "repositories");

        assertThat(repositories.lines()).containsExactly("orion", "team/repository", "zeta");
    }

    @Test
    void unknownSshExecCommandReturnsStableFailure() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);
        KeyPair serverIdentityKey = KeyUtils.readKeyFromFile(
                orionRoot.resolve("server-identity").resolve("signing-rsa.pem")
        ).valueOrFailure("Server identity key should be available after startup");

        SshCommandResult result = executeCommandOverSsh(
                startedOrion,
                "root",
                serverIdentityKey,
                "definitely-unknown"
        );

        assertThat(result.exitStatus()).isEqualTo(127);
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).isEqualTo("UNKNOWN_COMMAND: Unknown command\n");
    }

    @Test
    void interactivePtyEditsCompletesAndNeverStartsAnOperatingSystemShell() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        Path marker = tempDir.resolve("interactive-shell-marker");
        startedOrion = startFreshOrion(orionRoot);
        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();

        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        client.start();
        try (ClientSession session = client.connect(
                        "root",
                        startedOrion.configuration().getTransport().getSsh().getAddress(),
                        startedOrion.configuration().getTransport().getSsh().getPort())
                .verify(10, TimeUnit.SECONDS)
                .getSession()) {
            session.addPublicKeyIdentity(serverIdentityKey);
            session.auth().verify(10, TimeUnit.SECONDS);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PipedInputStream terminalInput = new PipedInputStream();
            try (PipedOutputStream clientInput = new PipedOutputStream(terminalInput);
                 ChannelShell channel = session.createShellChannel()) {
                channel.setPtyType("xterm-256color");
                channel.setPtyColumns(40);
                channel.setIn(terminalInput);
                channel.setOut(output);
                channel.setErr(new ByteArrayOutputStream());
                channel.open().verify(10, TimeUnit.SECONDS);
                awaitContains(output, "[root@orion] > ");

                send(clientInput, "help\r");
                awaitContains(output, "repositories");
                send(clientInput, "stateX\u001b[D\u001b[3~\r");
                awaitContains(output, "orion: RUNNING");
                send(clientInput, "\u001b[A\r");
                awaitOccurrences(output, "orion: RUNNING", 2);

                channel.sendWindowChange(20, 24, 0, 0);
                send(clientInput, "repo\t\r");
                awaitContains(output, "orion\n");
                send(clientInput, "touch " + marker + "; echo $(id) | cat >x `id`\r");
                awaitContains(output, "UNKNOWN_COMMAND: Unknown command");
                assertThat(Files.exists(marker)).isFalse();

                send(clientInput, "partial\u0004\u0003");
                awaitContains(output, "^C");
                clientInput.write(4);
                clientInput.flush();
                assertThat(channel.waitFor(
                                EnumSet.of(ClientChannelEvent.CLOSED),
                                TimeUnit.SECONDS.toMillis(10)))
                        .contains(ClientChannelEvent.CLOSED);
                assertThat(channel.getExitStatus()).isZero();
                assertThat(output.toString(StandardCharsets.UTF_8)).contains("\u001b[2K");
            }
        } finally {
            client.stop();
        }
    }

    @Test
    void regularUserCannotListRepositoriesOverSsh() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);
        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();
        String rootToken = issueTokenOverSsh(startedOrion, serverIdentityKey, 3_600);
        createManagedUser(startedOrion, rootToken, TRUSTED_USER_KEY, "project");

        SshCommandResult result = executeCommandOverSsh(
                startedOrion,
                USERNAME,
                TRUSTED_USER_KEY,
                "repositories"
        );

        assertThat(result.exitStatus()).isEqualTo(10);
        assertThat(result.output()).isEmpty();
    }

    @Test
    void rootSeesSameLifecycleStateOverSshAndHttp() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);

        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();

        String sshState = executeStateOverSsh(startedOrion, serverIdentityKey);
        String token = issueTokenOverSsh(startedOrion, serverIdentityKey, 600);
        String httpState = getAdminLifecycleStateWithBearerToken(startedOrion, token);

        assertThat(httpState).isEqualTo(sshState.stripTrailing());
        assertThat(sshState).contains("orion: RUNNING");
        assertThat(sshState).contains("executor: RUNNING");
        assertThat(sshState).contains("event-manager: RUNNING");
        assertThat(sshState).contains("access-control: RUNNING");
        assertThat(sshState).contains("transports: RUNNING");
        assertThat(sshState).contains("git-native: DISABLED");
        assertThat(sshState).contains("git-ssh: RUNNING");
        assertThat(sshState).contains("http: RUNNING");
    }

    @Test
    void sshAdminRoutesRemainAccessibleWhenTransportLifecycleIsRunning() throws Exception {
        /*
         * Verifies that the SSH admin channel works correctly when git-native is disabled but SSH is running.
         * Two things must hold simultaneously:
         *   1. The lifecycle state endpoint reports RUNNING for the transport layer.
         *   2. Other SSH admin commands (issue-token) remain functional, confirming the admin
         *      interface is not gated on transport state.
         */
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);

        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();

        String state = executeStateOverSsh(startedOrion, serverIdentityKey);
        assertThat(state).contains("orion: RUNNING");
        assertThat(state).contains("executor: RUNNING");
        assertThat(state).contains("event-manager: RUNNING");
        assertThat(state).contains("access-control: RUNNING");
        assertThat(state).contains("transports: RUNNING");
        assertThat(state).contains("git-native: DISABLED");
        assertThat(state).contains("git-ssh: RUNNING");
        assertThat(state).contains("http: RUNNING");

        String token = issueTokenOverSsh(startedOrion, serverIdentityKey, 600);
        assertThat(token).isNotBlank();
        assertThat(startedOrion.accessControlService().authenticateToken(token.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(pro.deta.orion.auth.AuthenticationResult.Success.class);

        HttpResponse acl = getAdminAclWithBearerToken(startedOrion, token);
        assertThat(acl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
    }

    @Test
    void shutdownCommandStopsServerPromptlyOverSsh() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        startedOrion = startFreshOrion(orionRoot);

        KeyPair serverIdentityKey = startedOrion.serverIdentityKey();

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
    void enrolledRootSshKeySurvivesServerRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-root");
        KeyPair enrolledKey = KeyUtils.generateRSAKeyPair()
                .valueOrFailure("Enrollment SSH key should be generated");
        startedOrion = startFreshOrion(orionRoot);
        char[] rootPassword = startedOrion.accessControlService()
                .plainRootToken(PlainRootTokenAccessForTests.create());
        try {
            assertThat(enrollKeyAndExecuteOverSsh(startedOrion, "root", rootPassword, enrolledKey, "state"))
                    .contains("orion: RUNNING");
        } finally {
            Arrays.fill(rootPassword, '\0');
        }

        startedOrion.stop();
        startedOrion = null;
        startedOrion = startExistingOrion(orionRoot);

        assertThat(executeStateOverSsh(startedOrion, enrolledKey)).contains("orion: RUNNING");
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
        try {
            TestServerIdentityMaterial identity = TestServerIdentityMaterial.open(configuration);
            OrionComponent component = DaggerOrionComponent.builder()
                    .configurationProvider(() -> configuration)
                    .runtimeOptions(OrionRuntimeOptions.defaults())
                    .serverIdentityCapability(identity.capability())
                    .build();
            OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            lifecycle.waitForStarting();

            return new StartedOrion(
                    configuration,
                    lifecycle,
                    nativeRepositoryProvider(configuration),
                    component.orionAccessControlService(),
                    identity);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot open test server identity", failure);
        }
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
        return executeRootCommandOverSsh(orion, keyPair, "issue-token " + expiresInSeconds).trim();
    }

    private static String executeStateOverSsh(StartedOrion orion, KeyPair keyPair) throws Exception {
        return executeRootCommandOverSsh(orion, keyPair, "state");
    }

    private static String executeRootCommandOverSsh(StartedOrion orion, KeyPair keyPair, String command) throws Exception {
        SshCommandResult result = executeCommandOverSsh(orion, "root", keyPair, command);
        assertThat(result.exitStatus())
                .as(command + " stderr: %s", result.error())
                .isEqualTo(0);
        return result.output();
    }

    private static String enrollKeyAndExecuteOverSsh(
            StartedOrion orion,
            String username,
            char[] password,
            KeyPair keyPair,
            String command) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        client.setUserAuthFactories(List.of(
                UserAuthPublicKeyFactory.INSTANCE,
                UserAuthKeyboardInteractiveFactory.INSTANCE));
        client.setUserInteraction(new UserInteraction() {
            @Override
            public String[] interactive(
                    ClientSession session,
                    String name,
                    String instruction,
                    String lang,
                    String[] prompt,
                    boolean[] echo) {
                if (prompt.length == 1 && "Orion password: ".equals(prompt[0]) && !echo[0]) {
                    return new String[]{new String(password)};
                }
                if (prompt.length == 1 && prompt[0].startsWith("Keys (`all`")) {
                    return new String[]{"all"};
                }
                throw new AssertionError("Unexpected SSH enrollment prompt: " + List.of(prompt));
            }

            @Override
            public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
                return null;
            }
        });
        client.start();
        try (ClientSession session = client.connect(
                        username,
                        orion.configuration().getTransport().getSsh().getAddress(),
                        orion.configuration().getTransport().getSsh().getPort())
                .verify(10, TimeUnit.SECONDS)
                .getSession()) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(10, TimeUnit.SECONDS);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            try (ClientChannel channel = session.createExecChannel(command)) {
                channel.setOut(output);
                channel.setErr(error);
                channel.open().verify(10, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(10));
                assertThat(channel.getExitStatus())
                        .as(command + " stderr: %s", error.toString(StandardCharsets.UTF_8))
                        .isEqualTo(0);
                return output.toString(StandardCharsets.UTF_8);
            }
        } finally {
            client.stop();
        }
    }

    private static SshCommandResult executeCommandOverSsh(
            StartedOrion orion,
            String username,
            KeyPair keyPair,
            String command) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        client.start();
        try (ClientSession session = client.connect(
                        username,
                        orion.configuration().getTransport().getSsh().getAddress(),
                        orion.configuration().getTransport().getSsh().getPort())
                .verify(10, TimeUnit.SECONDS)
                .getSession()) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(10, TimeUnit.SECONDS);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            try (ClientChannel channel = session.createExecChannel(command)) {
                channel.setOut(output);
                channel.setErr(error);
                channel.open().verify(10, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(10));
                return new SshCommandResult(
                        channel.getExitStatus(),
                        new String(output.toByteArray(), StandardCharsets.UTF_8),
                        new String(error.toByteArray(), StandardCharsets.UTF_8)
                );
            }
        } finally {
            client.stop();
        }
    }

    private static void send(PipedOutputStream input, String value) throws IOException {
        input.write(value.getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    private static void awaitContains(ByteArrayOutputStream output, String expected) throws Exception {
        awaitOccurrences(output, expected, 1);
    }

    private static void awaitOccurrences(
            ByteArrayOutputStream output,
            String expected,
            int expectedCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (occurrences(output.toString(StandardCharsets.UTF_8), expected) < expectedCount) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for terminal output: " + expected);
            }
            Thread.sleep(10);
        }
    }

    private static int occurrences(String value, String expected) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            result++;
            offset += expected.length();
        }
        return result;
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
        configuration.getBootstrap().getAccessControl().setRef("refs/heads/" + BRANCH);

        TestPorts.nextBatch().configure(configuration);
        configuration.getTransport().getGit().setEnabled(false);

        configuration.getTransport().getSsh().setEnabled(true);

        configuration.getTransport().getHttps().setEnabled(false);
        return configuration;
    }

    private static NativeGitRepositoryProvider nativeRepositoryProvider(OrionConfiguration configuration) {
        return new FileNativeGitRepositoryProvider(Path.of(URI.create(configuration.getStorage().getLocation())));
    }

    private void assertRepositoryContains(
            String repositoryName,
            ObjectId commitId,
            String fileName,
            String expectedContent) {
        assertRepositoryContains(startedOrion, repositoryName, commitId, fileName, expectedContent);
    }

    private static void assertRepositoryContains(StartedOrion orion, String repositoryName, ObjectId commitId,
                                                 String fileName, String expectedContent) {
        assertThat(orion.repositoryExists(repositoryName))
                .as("native repository %s exists after receiving %s", repositoryName, commitId.name())
                .isTrue();
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

    private static ObjectId pushCommitThroughSsh(
            StartedOrion orion,
            KeyPair keyPair,
            String repositoryName,
            Path sourceDirectory,
            Path sshHome,
            String content) throws Exception {
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(sshHome, keyPair);
             Git source = initRepository(sourceDirectory)) {
            ObjectId commit = createCommit(source, "state.txt", content, "managed user update");
            Iterable<PushResult> pushResults = source.push()
                    .setRemote(orion.sshUrl(repositoryName + ".git"))
                    .setTransportConfigCallback(sshCallback(ssh))
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();

            assertThat(pushResults)
                    .flatExtracting(PushResult::getRemoteUpdates)
                    .extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
            return commit;
        }
    }

    private static void assertSshRemoteDenied(StartedOrion orion, KeyPair keyPair, String repositoryName, Path sshHome)
            throws Exception {
        try (SshdSessionFactory ssh = acceptingPublicKeySshFactory(sshHome, keyPair)) {
            assertThatThrownBy(() -> Git.lsRemoteRepository()
                    .setRemote(orion.sshUrl(repositoryName + ".git"))
                    .setTransportConfigCallback(sshCallback(ssh))
                    .call())
                    .isInstanceOf(TransportException.class);
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
        seedAclRepository(orionRoot, accessControlFor(userKey.getPublic()));
    }

    private static void seedAclRepository(Path orionRoot, AccessControl accessControl) throws Exception {
        /*
         * ACL storage reads ACL from a native Orion repository named "orion". Seed it through the native
         * provider so the test writes the same on-disk format that production startup reads.
         */
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmlService().serialize(accessControl, output);
        new FileNativeGitRepositoryProvider(orionRoot.resolve("repos"))
                .create("orion")
                .valueOrFailure("ACL repository should be created")
                .saveFiles(
                        "refs/heads/" + BRANCH,
                        Map.of("orion.xml", output.toByteArray()),
                        "seed e2e access control",
                        new GitCommitAuthor("E2E Test", "e2e@example.test"));
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

    private static AccessControl accessControlForReadOnlyRepository(PublicKey userPublicKey, String repositoryName) {
        AccessControlDraft draft = new AccessControlDraft();
        AccessControlDraft.User user = ACLUtil.createUser(USERNAME, "e2e@example.test")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, KeyUtils.publicKeyToString(userPublicKey));
        user.addGrant("REPOSITORY_" + repositoryName)
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "*");
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

    private static void seedProjectRepository(Path orionRoot, String repositoryName, String content) throws Exception {
        new FileNativeGitRepositoryProvider(orionRoot.resolve("repos"))
                .create(repositoryName)
                .valueOrFailure("Project repository should be created")
                .saveFiles(
                        "refs/heads/" + BRANCH,
                        Map.of("README.md", content.getBytes(StandardCharsets.UTF_8)),
                        "seed " + repositoryName,
                        new GitCommitAuthor("E2E Test", "e2e@example.test"));
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

    private static HttpResponse getAdminAclWithBearerToken(StartedOrion orion, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) orion.httpUrl("/api/admin/acl").openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", TestBearerTokens.bearer(token));
        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        return new HttpResponse(status, contentType);
    }

    private static String getAdminLifecycleStateWithBearerToken(StartedOrion orion, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) orion.httpUrl("/api/admin/lifecycle/state").openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", TestBearerTokens.bearer(token));
        assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
        assertThat(connection.getContentType()).startsWith("text/plain");
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record HttpResponse(int status, String contentType) {
    }

    private record SshCommandResult(int exitStatus, String output, String error) {
    }

    private record StartedOrion(OrionConfiguration configuration, OrionApplicationLifecycle lifecycle,
                                NativeGitRepositoryProvider gitRepositoryProvider,
                                OrionAccessControlServiceImpl accessControlService,
                                TestServerIdentityMaterial identity) {
        private KeyPair serverIdentityKey() {
            return identity.keyPair();
        }

        private String sshUrl(String repository) {
            return "ssh://%s@%s:%d/%s".formatted(
                    "git",
                    configuration.getTransport().getSsh().getAddress(),
                    configuration.getTransport().getSsh().getPort(),
                    repository);
        }

        private boolean repositoryExists(String repository) {
            return gitRepositoryProvider.exists(repository);
        }

        private URL httpUrl(String path) throws IOException {
            return new URL(
                    "http",
                    configuration.getTransport().getHttp().getAddress(),
                    configuration.getTransport().getHttp().getPort(),
                    path);
        }

        private void stopSynchronously() {
            try {
                assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
            } finally {
                identity.close();
            }
        }

        private void stop() {
            try {
                lifecycle.beginShutdown();
                lifecycle.waitForShutdown();
            } finally {
                identity.close();
            }
        }
    }
}
