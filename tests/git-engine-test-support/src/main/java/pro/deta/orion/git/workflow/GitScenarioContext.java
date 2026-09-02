package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class GitScenarioContext {
    public static final String DEFAULT_BRANCH = "main";
    public static final String IDENTITY_NAME = "Parity";
    public static final String IDENTITY_EMAIL = "parity@example.test";
    public static final Instant COMMIT_TIME = Instant.EPOCH;
    public static final String REMOTE_REPOSITORY_NAME = "remote.git";

    private final GitClient client;
    private final GitServer server;
    private final GitRemoteRepository remote;
    private final Path directory;

    GitScenarioContext(GitClient client, GitServer server, GitRemoteRepository remote, Path directory) {
        this.client = Objects.requireNonNull(client, "client");
        this.server = Objects.requireNonNull(server, "server");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public GitClient client() {
        return client;
    }

    public GitServer server() {
        return server;
    }

    public GitRemoteRepository remote() {
        return remote;
    }

    public Path workTreeDirectory(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()
                || ".".equals(name)
                || "..".equals(name)
                || name.contains("/")
                || name.contains("\\")) {
            throw new IllegalArgumentException("Worktree name must be one path segment: " + name);
        }
        Path workTree = directory.resolve(name).normalize();
        if (!workTree.getParent().equals(directory.normalize())) {
            throw new IllegalArgumentException("Worktree must stay inside the invocation directory: " + name);
        }
        return Files.createDirectories(workTree);
    }

    public GitOperationResult perform(GitOperation operation) throws Exception {
        return Objects.requireNonNull(operation.run(), "operation result");
    }

    public GitOperationResult performAgainstRemote(GitOperation operation) throws Exception {
        RepositorySnapshot before = server.snapshot(remote);
        GitOperationResult result = perform(operation);
        RepositorySnapshot after = server.snapshot(remote);
        return result.withSnapshots(before, after);
    }
}
