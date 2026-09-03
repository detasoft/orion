package pro.deta.orion.git.workflow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class GitCliWorkflowClient implements GitClient {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Map<String, String> COMMIT_ENVIRONMENT = Map.of(
            "GIT_AUTHOR_NAME", GitScenarioContext.IDENTITY_NAME,
            "GIT_AUTHOR_EMAIL", GitScenarioContext.IDENTITY_EMAIL,
            "GIT_AUTHOR_DATE", GitScenarioContext.COMMIT_TIME.toString(),
            "GIT_COMMITTER_NAME", GitScenarioContext.IDENTITY_NAME,
            "GIT_COMMITTER_EMAIL", GitScenarioContext.IDENTITY_EMAIL,
            "GIT_COMMITTER_DATE", GitScenarioContext.COMMIT_TIME.toString());

    private final String name;
    private final GitCommandRunner commands;

    GitCliWorkflowClient(String name, String executable) {
        this.name = Objects.requireNonNull(name, "name");
        commands = new GitCommandRunner(executable, COMMAND_TIMEOUT);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        try {
            commands.version();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public void requireAvailable() {
        try {
            commands.version();
        } catch (Exception error) {
            throw new IllegalStateException("Canonical Git prerequisite is unavailable: "
                    + commands.executable() + ": " + error.getMessage(), error);
        }
    }

    @Override
    public String diagnostics() {
        try {
            return commands.version();
        } catch (Exception error) {
            return "canonical Git unavailable (" + commands.executable() + "): " + error.getMessage();
        }
    }

    @Override
    public GitWorkTree init(Path directory) throws Exception {
        commands.run(
                null,
                "init",
                "--initial-branch=" + GitScenarioContext.DEFAULT_BRANCH,
                directory.toString());
        return new GitCliWorkTree(this, directory);
    }

    @Override
    public GitWorkTree clone(String remoteUri, Path directory) throws Exception {
        commands.run(null, "clone", remoteUri, directory.toString());
        return new GitCliWorkTree(this, directory);
    }

    private static final class GitCliWorkTree implements GitWorkTree {
        private final GitCliWorkflowClient client;
        private final Path directory;

        private GitCliWorkTree(GitCliWorkflowClient client, Path directory) {
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
            client.commands.run(directory, arguments.toArray(String[]::new));
        }

        @Override
        public void commit(String message) throws Exception {
            client.commands.run(directory, COMMIT_ENVIRONMENT, "commit", "-m", message);
        }

        @Override
        public void addRemote(String name, GitRemoteRepository remote) throws Exception {
            client.commands.run(directory, "remote", "add", name, remote.uri());
        }

        @Override
        public void push(String remote, String branch) throws Exception {
            pushRefs(remote, "refs/heads/" + branch + ":refs/heads/" + branch);
        }

        @Override
        public GitOperationResult pushResult(String remote, String branch) throws Exception {
            GitCommandRunner.Result result = client.commands.runResult(
                    directory,
                    "push",
                    "--porcelain",
                    remote,
                    "refs/heads/" + branch + ":refs/heads/" + branch);
            if (result.successful()) {
                return GitOperationResult.accepted();
            }
            for (String line : result.output().split("\\R")) {
                String[] fields = line.split("\\t", 3);
                if (fields.length == 3
                        && "!".equals(fields[0])
                        && ("[rejected] (non-fast-forward)".equals(fields[2])
                        || "[rejected] (fetch first)".equals(fields[2]))) {
                    return GitOperationResult.nonFastForward("remote rejected non-fast-forward update");
                }
            }
            result.requireSuccess();
            throw new IllegalStateException("Unreachable canonical Git push result");
        }

        @Override
        public void pushRefs(String remote, String... refSpecs) throws Exception {
            List<String> arguments = new ArrayList<>();
            arguments.add("push");
            arguments.add(remote);
            arguments.addAll(List.of(refSpecs));
            client.commands.run(directory, arguments.toArray(String[]::new));
        }

        @Override
        public void updateRef(String refName, String target) throws Exception {
            client.commands.run(directory, "update-ref", refName, target);
        }

        @Override
        public void fetch(String remote) throws Exception {
            client.commands.run(directory, "fetch", remote);
        }

        @Override
        public void fetch(String remote, String branch) throws Exception {
            client.commands.run(directory, "fetch", remote,
                    "+refs/heads/" + branch + ":refs/remotes/" + remote + "/" + branch);
        }

        @Override
        public void pull(String remote, String branch) throws Exception {
            client.commands.run(directory, "pull", "--ff-only", remote, branch);
        }

        @Override
        public void checkout(String branch, String startPoint) throws Exception {
            client.commands.run(directory, "checkout", "-b", branch, startPoint);
        }

        @Override
        public String head() throws Exception {
            return client.commands.run(directory, "rev-parse", "HEAD").trimmed();
        }
    }
}
