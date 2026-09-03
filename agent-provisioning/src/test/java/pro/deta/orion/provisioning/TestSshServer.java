package pro.deta.orion.provisioning;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TestSshServer implements AutoCloseable {
    private final Path root;
    private final SshServer server;
    private final KeyPair hostKey;
    private final List<String> commands;
    private final List<byte[]> commandInputs;
    private final AtomicInteger publicKeyAttempts;
    private final AtomicInteger passwordAttempts;
    private final List<Integer> publicKeySessions;
    private final List<Integer> passwordSessions;

    private TestSshServer(
            Path root,
            SshServer server,
            KeyPair hostKey,
            List<String> commands,
            List<byte[]> commandInputs,
            AtomicInteger publicKeyAttempts,
            AtomicInteger passwordAttempts,
            List<Integer> publicKeySessions,
            List<Integer> passwordSessions) {
        this.root = root;
        this.server = server;
        this.hostKey = hostKey;
        this.commands = commands;
        this.commandInputs = commandInputs;
        this.publicKeyAttempts = publicKeyAttempts;
        this.passwordAttempts = passwordAttempts;
        this.publicKeySessions = publicKeySessions;
        this.passwordSessions = passwordSessions;
    }

    static TestSshServer start(Path root, KeyPair hostKey, KeyPair authorizedClient) throws Exception {
        return start(root, hostKey, authorizedClient, Duration.ZERO, Duration.ZERO);
    }

    static TestSshServer start(
            Path root,
            KeyPair hostKey,
            KeyPair authorizedClient,
            Duration authenticationDelay,
            Duration commandStartDelay) throws Exception {
        return start(root, hostKey, authorizedClient, null, authenticationDelay, commandStartDelay);
    }

    static TestSshServer startWithPassword(
            Path root,
            KeyPair hostKey,
            KeyPair authorizedClient,
            String password) throws Exception {
        return start(root, hostKey, authorizedClient, password, Duration.ZERO, Duration.ZERO);
    }

    static TestSshServer startEnrollable(Path root, KeyPair hostKey, String password) throws Exception {
        return start(root, hostKey, null, password, Duration.ZERO, Duration.ZERO);
    }

    static TestSshServer startEnrollableRejectingFirstPublicKeySession(
            Path root,
            KeyPair hostKey,
            String password) throws Exception {
        return start(
                root, hostKey, null, password,
                Duration.ZERO, Duration.ZERO, true, Duration.ZERO, true);
    }

    private static TestSshServer start(
            Path root,
            KeyPair hostKey,
            KeyPair authorizedClient,
            String password,
            Duration authenticationDelay,
            Duration commandStartDelay) throws Exception {
        return start(
                root, hostKey, authorizedClient, password,
                authenticationDelay, commandStartDelay, true, Duration.ZERO, false);
    }

    static TestSshServer startEnrollableRejectingVerification(
            Path root,
            KeyPair hostKey,
            String password) throws Exception {
        return start(
                root, hostKey, null, password,
                Duration.ZERO, Duration.ZERO, false, Duration.ZERO, false);
    }

    static TestSshServer startEnrollableWithVerificationDelay(
            Path root,
            KeyPair hostKey,
            String password,
            Duration verificationDelay) throws Exception {
        return start(
                root, hostKey, null, password,
                Duration.ZERO, Duration.ZERO, true, verificationDelay, false);
    }

    private static TestSshServer start(
            Path root,
            KeyPair hostKey,
            KeyPair authorizedClient,
            String password,
            Duration authenticationDelay,
            Duration commandStartDelay,
            boolean acceptEnrolledKey,
            Duration verificationDelay,
            boolean rejectFirstPublicKeySession) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        AtomicInteger publicKeyAttempts = new AtomicInteger();
        AtomicInteger passwordAttempts = new AtomicInteger();
        List<Integer> publicKeySessions = new CopyOnWriteArrayList<>();
        List<Integer> passwordSessions = new CopyOnWriteArrayList<>();
        AtomicInteger firstPublicKeySession = new AtomicInteger();
        server.setPublickeyAuthenticator((username, key, session) -> {
            publicKeyAttempts.incrementAndGet();
            int sessionIdentity = System.identityHashCode(session);
            publicKeySessions.add(sessionIdentity);
            firstPublicKeySession.compareAndSet(0, sessionIdentity);
            boolean enrolled = authorizedClient == null && isAuthorized(root, key);
            delay(enrolled ? verificationDelay : authenticationDelay);
            return "orion".equals(username)
                    && (!rejectFirstPublicKeySession || sessionIdentity != firstPublicKeySession.get())
                    && (authorizedClient == null
                    ? acceptEnrolledKey && enrolled
                    : org.apache.sshd.common.config.keys.KeyUtils.compareKeys(
                            authorizedClient.getPublic(), key));
        });
        server.setPasswordAuthenticator((username, supplied, session) -> {
            passwordAttempts.incrementAndGet();
            passwordSessions.add(System.identityHashCode(session));
            delay(authenticationDelay);
            return "orion".equals(username) && password != null && password.equals(supplied);
        });
        List<String> commands = new CopyOnWriteArrayList<>();
        List<byte[]> commandInputs = new CopyOnWriteArrayList<>();
        server.setCommandFactory((channel, command) -> {
            commands.add(command);
            return new ShellCommand(
                    root, command, commandStartDelay,
                    authorizedClient == null ? commandInputs : null);
        });
        server.start();
        return new TestSshServer(
                root, server, hostKey, commands, commandInputs, publicKeyAttempts, passwordAttempts,
                publicKeySessions, passwordSessions);
    }

    private static boolean isAuthorized(Path root, PublicKey key) {
        Path authorizedKeys = root.resolve(".ssh/authorized_keys");
        if (!Files.isRegularFile(authorizedKeys)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(authorizedKeys, StandardCharsets.UTF_8)) {
                try {
                    AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line);
                    if (entry != null && KeyUtils.compareKeys(
                            entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING), key)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // OpenSSH ignores malformed authorized_keys records and continues scanning.
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static void delay(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    SshEndpoint endpoint() {
        return new SshEndpoint("127.0.0.1", server.getPort(), "orion", hostKey.getPublic());
    }

    Path root() {
        return root;
    }

    List<String> commands() {
        return List.copyOf(commands);
    }

    List<byte[]> commandInputs() {
        List<byte[]> copied = new java.util.ArrayList<>(commandInputs.size());
        for (byte[] bytes : commandInputs) {
            copied.add(Arrays.copyOf(bytes, bytes.length));
        }
        return List.copyOf(copied);
    }

    int publicKeyAttempts() {
        return publicKeyAttempts.get();
    }

    int passwordAttempts() {
        return passwordAttempts.get();
    }

    List<Integer> publicKeySessions() {
        return List.copyOf(publicKeySessions);
    }

    List<Integer> passwordSessions() {
        return List.copyOf(passwordSessions);
    }

    boolean hasActiveSessions() {
        return !server.getActiveSessions().isEmpty();
    }

    @Override
    public void close() throws Exception {
        server.stop(true);
    }

    private static final class ShellCommand implements Command {
        private final Path root;
        private final String command;
        private final Duration startDelay;
        private final List<byte[]> recordedInputs;
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback exit;
        private Process process;
        private Thread worker;

        private ShellCommand(
                Path root,
                String command,
                Duration startDelay,
                List<byte[]> recordedInputs) {
            this.root = root;
            this.command = command;
            this.startDelay = startDelay;
            this.recordedInputs = recordedInputs;
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
            delay(startDelay);
            worker = Thread.ofVirtual().name("test-ssh-command").start(this::run);
        }

        private void run() {
            int exitCode = 1;
            try {
                ProcessBuilder builder = new ProcessBuilder("sh", "-c", command)
                        .directory(root.toFile());
                builder.environment().put("HOME", root.toString());
                process = builder.start();
                Thread inputPump = Thread.ofVirtual().start(
                        () -> transferInput(input, process.getOutputStream()));
                Thread outputPump = Thread.ofVirtual().start(() -> transfer(process.getInputStream(), output));
                Thread errorPump = Thread.ofVirtual().start(() -> transfer(process.getErrorStream(), error));
                exitCode = process.waitFor();
                inputPump.join();
                outputPump.join();
                errorPump.join();
            } catch (Exception exception) {
                try {
                    error.write(exception.getClass().getSimpleName()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // The client may already have closed the channel.
                }
            } finally {
                exit.onExit(exitCode);
            }
        }

        private void transferInput(InputStream input, OutputStream output) {
            if (recordedInputs == null) {
                transfer(input, output);
                return;
            }
            try (output) {
                byte[] bytes = input.readAllBytes();
                recordedInputs.add(Arrays.copyOf(bytes, bytes.length));
                output.write(bytes);
            } catch (Exception ignored) {
                // Closing the SSH channel is expected to interrupt the input pump.
            }
        }

        private static void transfer(InputStream input, OutputStream output) {
            try (output) {
                input.transferTo(output);
            } catch (Exception ignored) {
                // Closing the SSH channel is expected to interrupt pumps.
            }
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            if (process != null) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
