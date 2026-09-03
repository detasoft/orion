package pro.deta.orion.provisioning;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

final class TestSshServer implements AutoCloseable {
    private final Path root;
    private final SshServer server;
    private final KeyPair hostKey;
    private final List<String> commands;

    private TestSshServer(Path root, SshServer server, KeyPair hostKey, List<String> commands) {
        this.root = root;
        this.server = server;
        this.hostKey = hostKey;
        this.commands = commands;
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
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPublickeyAuthenticator((username, key, session) -> {
            delay(authenticationDelay);
            return "orion".equals(username)
                    && org.apache.sshd.common.config.keys.KeyUtils.compareKeys(
                            authorizedClient.getPublic(), key);
        });
        List<String> commands = new CopyOnWriteArrayList<>();
        server.setCommandFactory((channel, command) -> {
            commands.add(command);
            return new ShellCommand(root, command, commandStartDelay);
        });
        server.start();
        return new TestSshServer(root, server, hostKey, commands);
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
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback exit;
        private Process process;
        private Thread worker;

        private ShellCommand(Path root, String command, Duration startDelay) {
            this.root = root;
            this.command = command;
            this.startDelay = startDelay;
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
                process = new ProcessBuilder("sh", "-c", command)
                        .directory(root.toFile())
                        .start();
                Thread inputPump = Thread.ofVirtual().start(() -> transfer(input, process.getOutputStream()));
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
