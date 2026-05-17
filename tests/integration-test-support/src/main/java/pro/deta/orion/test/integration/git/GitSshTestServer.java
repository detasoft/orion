package pro.deta.orion.test.integration.git;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;

public final class GitSshTestServer implements AutoCloseable {
    private final Path repositoriesRoot;
    private final String username;
    private final KeyPair hostKey;
    private final SshServer server;

    private GitSshTestServer(Path repositoriesRoot, String username, KeyPair hostKey, SshServer server) {
        this.repositoriesRoot = repositoriesRoot.toAbsolutePath().normalize();
        this.username = username;
        this.hostKey = hostKey;
        this.server = server;
    }

    public static GitSshTestServer start(
            Path repositoriesRoot,
            String username,
            KeyPair hostKey,
            PublicKey authorizedKey) throws Exception {
        return start(repositoriesRoot, username, hostKey, authorizedKey, 0);
    }

    public static GitSshTestServer start(
            Path repositoriesRoot,
            String username,
            KeyPair hostKey,
            PublicKey authorizedKey,
            int port) throws Exception {
        Files.createDirectories(repositoriesRoot);

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("localhost");
        sshd.setPort(port);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPublickeyAuthenticator((user, key, session) ->
                username.equals(user)
                        && org.apache.sshd.common.config.keys.KeyUtils.compareKeys(authorizedKey, key));
        sshd.setPasswordAuthenticator((user, password, session) -> false);

        GitSshTestServer testServer = new GitSshTestServer(repositoriesRoot, username, hostKey, sshd);
        sshd.setCommandFactory((channel, commandLine) -> testServer.command(commandLine));
        sshd.start();
        return testServer;
    }

    public String repositoryUrl(String repositoryName) {
        return "ssh://" + username + "@localhost:" + server.getPort() + "/" + repositoryName;
    }

    public String knownHostsLine() {
        return "[localhost]:" + server.getPort() + " " + PublicKeyEntry.toString(hostKey.getPublic());
    }

    @Override
    public void close() throws Exception {
        server.stop(true);
    }

    private Command command(String commandLine) throws java.io.IOException {
        List<String> parts = CommandFactory.split(commandLine);
        if (parts.size() != 2) {
            throw new java.io.IOException("Unsupported Git SSH command: " + commandLine);
        }
        return switch (parts.get(0)) {
            case "git-upload-pack" -> new UploadPackCommand(repositoriesRoot, parts.get(1));
            case "git-receive-pack" -> new ReceivePackCommand(repositoriesRoot, parts.get(1));
            default -> throw new java.io.IOException("Unsupported Git SSH command: " + commandLine);
        };
    }

    private abstract static class GitPackCommand implements Command, Runnable {
        private final Path repositoriesRoot;
        private final String repositoryName;
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback callback;
        private Thread thread;

        private GitPackCommand(Path repositoriesRoot, String repositoryName) {
            this.repositoriesRoot = repositoriesRoot;
            this.repositoryName = repositoryName;
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
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            thread = new Thread(this, threadName());
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try (Repository repository = openRepository()) {
                runPack(repository, input, output, error);
                exit(0, "");
            } catch (Exception e) {
                exit(1, e.getMessage());
            }
        }

        protected abstract String threadName();

        protected abstract void runPack(Repository repository, InputStream input, OutputStream output, OutputStream error)
                throws Exception;

        private Repository openRepository() throws java.io.IOException {
            Path repositoryPath = repositoriesRoot.resolve(stripLeadingSlash(repositoryName)).normalize();
            if (!repositoryPath.startsWith(repositoriesRoot) || !Files.exists(repositoryPath)) {
                throw new RepositoryNotFoundException(repositoryName);
            }
            return new FileRepositoryBuilder()
                    .setGitDir(repositoryPath.toFile())
                    .build();
        }

        private void exit(int code, String message) {
            if (callback != null) {
                callback.onExit(code, message);
            }
        }

        private static String stripLeadingSlash(String value) {
            while (value.startsWith("/")) {
                value = value.substring(1);
            }
            return value;
        }
    }

    private static final class UploadPackCommand extends GitPackCommand {
        private UploadPackCommand(Path repositoriesRoot, String repositoryName) {
            super(repositoriesRoot, repositoryName);
        }

        @Override
        protected String threadName() {
            return "orion-test-git-upload-pack";
        }

        @Override
        protected void runPack(Repository repository, InputStream input, OutputStream output, OutputStream error)
                throws Exception {
            new UploadPack(repository).upload(input, output, error);
        }
    }

    private static final class ReceivePackCommand extends GitPackCommand {
        private ReceivePackCommand(Path repositoriesRoot, String repositoryName) {
            super(repositoriesRoot, repositoryName);
        }

        @Override
        protected String threadName() {
            return "orion-test-git-receive-pack";
        }

        @Override
        protected void runPack(Repository repository, InputStream input, OutputStream output, OutputStream error)
                throws Exception {
            new ReceivePack(repository).receive(input, output, error);
        }
    }
}
