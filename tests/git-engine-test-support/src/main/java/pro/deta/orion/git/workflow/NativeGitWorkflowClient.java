package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class NativeGitWorkflowClient implements GitClient {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Map<String, String> COMMIT_ENVIRONMENT = Map.of(
            "GIT_AUTHOR_NAME", "Parity",
            "GIT_AUTHOR_EMAIL", "parity@example.test",
            "GIT_AUTHOR_DATE", "1970-01-01T00:00:00+0000",
            "GIT_COMMITTER_NAME", "Parity",
            "GIT_COMMITTER_EMAIL", "parity@example.test",
            "GIT_COMMITTER_DATE", "1970-01-01T00:00:00+0000");

    private final String name;
    private final String executable;

    NativeGitWorkflowClient(String name, String executable) {
        this.name = Objects.requireNonNull(name, "name");
        this.executable = Objects.requireNonNull(executable, "executable");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        try {
            command(null, "--version");
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public GitWorkTree init(Path directory) throws Exception {
        command(null, "init", "--initial-branch=main", directory.toString());
        return new NativeGitWorkTree(this, directory);
    }

    @Override
    public GitWorkTree clone(String remoteUri, Path directory) throws Exception {
        command(null, "clone", remoteUri, directory.toString());
        return new NativeGitWorkTree(this, directory);
    }

    private GitCommandOutput command(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-c");
        command.add("commit.gpgSign=false");
        command.add("-c");
        command.add("user.name=Parity");
        command.add("-c");
        command.add("user.email=parity@example.test");
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.redirectErrorStream(true);
        builder.environment().putAll(COMMIT_ENVIRONMENT);

        Process process = builder.start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out running " + String.join(" ", command));
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(
                    "Command failed with exit " + process.exitValue() + ": "
                            + String.join(" ", command) + "\n" + output);
        }
        return new GitCommandOutput(output);
    }

    private record GitCommandOutput(String output) {
        private String trimmed() {
            return output.strip();
        }
    }

    private static final class NativeGitWorkTree implements GitWorkTree {
        private final NativeGitWorkflowClient client;
        private final Path directory;

        private NativeGitWorkTree(NativeGitWorkflowClient client, Path directory) {
            this.client = client;
            this.directory = directory;
        }

        @Override
        public GitClient client() {
            return client;
        }

        @Override
        public Path directory() {
            return directory;
        }

        @Override
        public void add(String... pathspecs) throws Exception {
            List<String> arguments = new ArrayList<>();
            arguments.add("add");
            arguments.addAll(List.of(pathspecs));
            client.command(directory, arguments.toArray(String[]::new));
        }

        @Override
        public void commit(String message) throws Exception {
            client.command(directory, "commit", "-m", message);
        }

        @Override
        public void addRemote(String name, GitRemoteRepository remote) throws Exception {
            client.command(directory, "remote", "add", name, remote.uri());
        }

        @Override
        public void push(String remote, String branch) throws Exception {
            client.command(directory, "push", remote, branch);
        }

        @Override
        public void fetch(String remote) throws Exception {
            client.command(directory, "fetch", remote);
        }

        @Override
        public void pull(String remote, String branch) throws Exception {
            client.command(directory, "pull", "--ff-only", remote, branch);
        }

        @Override
        public String head() throws Exception {
            return client.command(directory, "rev-parse", "HEAD").trimmed();
        }
    }
}
