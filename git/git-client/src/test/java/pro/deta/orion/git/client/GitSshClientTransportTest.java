package pro.deta.orion.git.client;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitSshClientTransportTest {
    @Test
    void rejectsUnknownServerHostKeyWithVerificationFailure(
            @TempDir Path temporaryDirectory) throws Exception {
        TestRepository repository = createRepository(temporaryDirectory);
        Path knownHosts = temporaryDirectory.resolve("known_hosts");
        Files.writeString(knownHosts, "");
        try (TestSshServer server = TestSshServer.start(
                temporaryDirectory, repository.path());
             GitSshClientTransport transport = GitSshClientTransport.strictKnownHosts(
                     knownHosts, GitSshSessionAuthenticator.password("password"))) {
            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport).discover(
                            server.repositoryUri(), GitClientOptions.defaults());

            assertThat(result).isInstanceOf(GitClientResult.Failed.class);
            assertThat(failure(result).kind()).isEqualTo(
                    GitClientFailure.Kind.VERIFICATION_FAILED);
        }
    }

    @Test
    void acceptsKnownServerHostKey(@TempDir Path temporaryDirectory) throws Exception {
        TestRepository repository = createRepository(temporaryDirectory);
        Path knownHosts = temporaryDirectory.resolve("known_hosts");
        try (TestSshServer server = TestSshServer.start(
                temporaryDirectory, repository.path())) {
            Files.writeString(knownHosts, server.knownHostEntry());
            try (GitSshClientTransport transport = GitSshClientTransport.strictKnownHosts(
                    knownHosts, GitSshSessionAuthenticator.password("password"))) {
                GitClientResult<GitRemoteAdvertisement> result =
                        new GitUploadPackClient(transport).discover(
                                server.repositoryUri(), GitClientOptions.defaults());

                assertThat(result).isInstanceOf(GitClientResult.Success.class);
            }
        }
    }

    @Test
    void rejectsChangedServerHostKeyWithVerificationFailure(
            @TempDir Path temporaryDirectory) throws Exception {
        TestRepository repository = createRepository(temporaryDirectory);
        Path knownHosts = temporaryDirectory.resolve("known_hosts");
        int port;
        try (TestSshServer original = TestSshServer.start(
                temporaryDirectory.resolve("original-key"), repository.path(), 0)) {
            port = original.port();
            Files.writeString(knownHosts, original.knownHostEntry());
        }
        try (TestSshServer changed = TestSshServer.start(
                temporaryDirectory.resolve("changed-key"), repository.path(), port);
             GitSshClientTransport transport = GitSshClientTransport.strictKnownHosts(
                     knownHosts, GitSshSessionAuthenticator.password("password"))) {
            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport).discover(
                            changed.repositoryUri(), GitClientOptions.defaults());

            assertThat(result).isInstanceOf(GitClientResult.Failed.class);
            assertThat(failure(result).kind()).isEqualTo(
                    GitClientFailure.Kind.VERIFICATION_FAILED);
        }
    }

    @Test
    void mapsAuthenticationTimeoutToTimeout(@TempDir Path temporaryDirectory)
            throws Exception {
        TestRepository repository = createRepository(temporaryDirectory);
        PasswordAuthenticator stalledAuthentication = (user, password, session) -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return false;
        };
        try (TestSshServer server = TestSshServer.start(
                temporaryDirectory, repository.path(), stalledAuthentication);
             SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, address, key) -> true);
            client.start();
            GitSshClientTransport transport = new GitSshClientTransport(
                    client, GitSshSessionAuthenticator.password("password"));
            Duration timeout = Duration.ofMillis(50);
            GitClientOptions options = new GitClientOptions(
                    timeout, timeout, timeout, Duration.ofSeconds(1), 1);

            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport).discover(
                            server.repositoryUri(), options);

            assertThat(failure(result).kind()).isEqualTo(GitClientFailure.Kind.TIMEOUT);
        }
    }

    @Test
    void fetchesAndPushesAgainstSshGitServer(@TempDir Path temporaryDirectory)
            throws Exception {
        TestRepository repository = createRepository(temporaryDirectory);
        try (TestSshServer server = TestSshServer.start(
                temporaryDirectory, repository.path());
             SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, address, key) -> true);
            client.start();
            GitSshClientTransport transport = new GitSshClientTransport(
                    client, GitSshSessionAuthenticator.password("password"));
            ByteArrayOutputStream pack = new ByteArrayOutputStream();

            GitClientResult<GitUploadPackResult> fetch =
                    new GitUploadPackClient(transport).fetch(
                            server.repositoryUri(),
                            GitClientOptions.defaults(),
                            GitUploadPackRequest.of(
                                    repository.commitId(),
                                    new OutputStreamBufferedByteOutput(pack)));

            assertThat(fetch).isInstanceOf(GitClientResult.Success.class);
            assertThat(pack.toByteArray()).startsWith(
                    "PACK".getBytes(StandardCharsets.US_ASCII));

            GitReceivePackRequest delete = new GitReceivePackRequest(
                    List.of(new GitReceivePackRequest.Command(
                            repository.commitId(),
                            GitClientValidation.NULL_ID,
                            "refs/heads/delete-me")),
                    output -> { });
            GitClientResult<GitReceivePackResult> push =
                    new GitReceivePackClient(transport).push(
                            server.repositoryUri(),
                            GitClientOptions.defaults(),
                            delete);

            assertThat(push).isInstanceOf(GitClientResult.Success.class);
            assertThat(success(push).accepted()).isTrue();
        }

        try (Repository checked = new FileRepositoryBuilder()
                .setGitDir(repository.path().toFile())
                .build()) {
            assertThat(checked.exactRef("refs/heads/delete-me")).isNull();
        }
    }

    @Test
    void rejectsPasswordEmbeddedInUri() throws Exception {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            GitSshClientTransport transport = new GitSshClientTransport(
                    client, GitSshSessionAuthenticator.defaultIdentities());

            assertThatThrownBy(() -> transport.open(
                    GitClientService.UPLOAD_PACK,
                    URI.create("ssh://user:secret@example.invalid/repository.git"),
                    GitClientOptions.defaults()))
                    .isInstanceOf(GitClientTransportException.class)
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void rejectsUrisWithoutUserBeforeConnecting() throws Exception {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            GitSshClientTransport transport = new GitSshClientTransport(
                    client, GitSshSessionAuthenticator.defaultIdentities());

            assertThatThrownBy(() -> transport.open(
                    GitClientService.UPLOAD_PACK,
                    URI.create("ssh://example.invalid/repository.git"),
                    GitClientOptions.defaults()))
                    .isInstanceOf(GitClientTransportException.class)
                    .extracting(error -> ((GitClientTransportException) error).kind())
                    .isEqualTo(GitClientFailure.Kind.PROTOCOL_UNSUPPORTED);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(GitClientResult<T> result) {
        return ((GitClientResult.Success<T>) result).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> GitClientFailure failure(GitClientResult<T> result) {
        return ((GitClientResult.Failed<T>) result).failure();
    }

    private static TestRepository createRepository(Path temporaryDirectory)
            throws Exception {
        Path seedDirectory = temporaryDirectory.resolve("seed");
        ObjectId commitId;
        try (Git seed = Git.init()
                .setDirectory(seedDirectory.toFile())
                .setInitialBranch("main")
                .call()) {
            Files.writeString(seedDirectory.resolve("README.md"), "SSH fixture\n");
            seed.add().addFilepattern("README.md").call();
            commitId = seed.commit()
                    .setMessage("Seed repository")
                    .setAuthor("Orion Test", "orion@example.invalid")
                    .setCommitter("Orion Test", "orion@example.invalid")
                    .call();
        }
        Path bareRepository = temporaryDirectory.resolve("repository.git");
        try (Git remote = Git.cloneRepository()
                .setURI(seedDirectory.toUri().toString())
                .setDirectory(bareRepository.toFile())
                .setBare(true)
                .call()) {
            RefUpdate branch = remote.getRepository()
                    .updateRef("refs/heads/delete-me");
            branch.setNewObjectId(commitId);
            assertThat(branch.update()).isEqualTo(RefUpdate.Result.NEW);
        }
        return new TestRepository(bareRepository, commitId.name());
    }

    private record TestRepository(Path path, String commitId) {
    }

    private static final class TestSshServer implements AutoCloseable {
        private final SshServer server;
        private final SimpleGeneratorHostKeyProvider keyProvider;

        private TestSshServer(
                SshServer server,
                SimpleGeneratorHostKeyProvider keyProvider) {
            this.server = server;
            this.keyProvider = keyProvider;
        }

        private static TestSshServer start(
                Path temporaryDirectory,
                Path repositoryPath) throws Exception {
            return start(temporaryDirectory, repositoryPath,
                    (user, password, session) ->
                            "git".equals(user) && "password".equals(password));
        }

        private static TestSshServer start(
                Path temporaryDirectory,
                Path repositoryPath,
                PasswordAuthenticator passwordAuthenticator) throws Exception {
            return start(temporaryDirectory.resolve("host-key"), repositoryPath, 0,
                    passwordAuthenticator);
        }

        private static TestSshServer start(
                Path hostKey,
                Path repositoryPath,
                int port) throws Exception {
            return start(hostKey, repositoryPath, port,
                    (user, password, session) ->
                            "git".equals(user) && "password".equals(password));
        }

        private static TestSshServer start(
                Path hostKey,
                Path repositoryPath,
                int port,
                PasswordAuthenticator passwordAuthenticator) throws Exception {
            SshServer server = SshServer.setUpDefaultServer();
            server.setHost("127.0.0.1");
            server.setPort(port);
            SimpleGeneratorHostKeyProvider keyProvider =
                    new SimpleGeneratorHostKeyProvider(hostKey);
            server.setKeyPairProvider(keyProvider);
            server.setPasswordAuthenticator(passwordAuthenticator);
            server.setCommandFactory((channel, command) ->
                    new GitCommand(repositoryPath, command));
            server.start();
            return new TestSshServer(server, keyProvider);
        }

        private String knownHostEntry() {
            return "[127.0.0.1]:" + server.getPort() + " "
                    + PublicKeyEntry.toString(keyProvider.loadKeys(null).getFirst().getPublic())
                    + "\n";
        }

        private int port() {
            return server.getPort();
        }

        private URI repositoryUri() {
            return URI.create("ssh://git@127.0.0.1:"
                    + server.getPort() + "/repository.git");
        }

        @Override
        public void close() throws Exception {
            server.stop(true);
        }
    }

    private static final class GitCommand implements Command {
        private final Path repositoryPath;
        private final String command;
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback exit;
        private Thread worker;

        private GitCommand(Path repositoryPath, String command) {
            this.repositoryPath = repositoryPath;
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream input) {
            this.input = input;
        }

        @Override
        public void setOutputStream(OutputStream output) {
            this.output = output;
        }

        @Override
        public void setErrorStream(OutputStream error) {
            this.error = error;
        }

        @Override
        public void setExitCallback(ExitCallback exit) {
            this.exit = exit;
        }

        @Override
        public void start(ChannelSession channel, Environment environment) {
            worker = Thread.ofVirtual().start(this::run);
        }

        private void run() {
            try (Repository repository = new FileRepositoryBuilder()
                    .setGitDir(repositoryPath.toFile())
                    .build()) {
                if (command.startsWith(GitClientService.UPLOAD_PACK.command())) {
                    new UploadPack(repository).upload(input, output, error);
                } else if (command.startsWith(
                        GitClientService.RECEIVE_PACK.command())) {
                    ReceivePack receivePack = new ReceivePack(repository);
                    receivePack.setAllowDeletes(true);
                    receivePack.receive(input, output, error);
                } else {
                    throw new IllegalArgumentException("Unsupported Git command");
                }
                exit.onExit(0);
            } catch (Exception exception) {
                exit.onExit(1, "Git command failed");
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
