package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.nio.file.Path;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

final class JGitWorkflowClient implements GitClient {
    private static final PersonIdent PARITY_IDENTITY = new PersonIdent(
            GitScenarioContext.IDENTITY_NAME,
            GitScenarioContext.IDENTITY_EMAIL,
            Date.from(GitScenarioContext.COMMIT_TIME),
            TimeZone.getTimeZone("UTC"));

    private final String name;

    JGitWorkflowClient(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public GitWorkTree init(Path directory) throws Exception {
        Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch(GitScenarioContext.DEFAULT_BRANCH)
                .call();
        return new JGitWorkTree(this, directory, git);
    }

    @Override
    public GitWorkTree clone(String remoteUri, Path directory) throws Exception {
        Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setDirectory(directory.toFile())
                .call();
        return new JGitWorkTree(this, directory, git);
    }

    private static final class JGitWorkTree implements GitWorkTree {
        private final GitClient client;
        private final Path directory;
        private final Git git;

        private JGitWorkTree(GitClient client, Path directory, Git git) {
            this.client = client;
            this.directory = directory;
            this.git = git;
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
            for (String pathspec : pathspecs) {
                git.add().addFilepattern(pathspec).call();
            }
        }

        @Override
        public void commit(String message) throws Exception {
            git.commit()
                    .setMessage(message)
                    .setAuthor(PARITY_IDENTITY)
                    .setCommitter(PARITY_IDENTITY)
                    .call();
        }

        @Override
        public void addRemote(String name, GitRemoteRepository remote) throws Exception {
            git.remoteAdd()
                    .setName(name)
                    .setUri(new URIish(remote.uri()))
                    .call();
        }

        @Override
        public void push(String remote, String branch) throws Exception {
            var results = git.push()
                    .setRemote(remote)
                    .add("refs/heads/" + branch)
                    .call();
            for (var result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    requireAcceptedPush(update);
                }
            }
        }

        @Override
        public void fetch(String remote) throws Exception {
            git.fetch().setRemote(remote).call();
        }

        @Override
        public void pull(String remote, String branch) throws Exception {
            PullResult result = git.pull()
                    .setRemote(remote)
                    .setRemoteBranchName(branch)
                    .call();
            if (!result.isSuccessful()) {
                throw new IllegalStateException("JGit pull failed: " + result);
            }
        }

        @Override
        public String head() throws Exception {
            return git.getRepository().resolve("HEAD").name();
        }

        @Override
        public void close() {
            git.close();
        }

        private static void requireAcceptedPush(RemoteRefUpdate update) {
            RemoteRefUpdate.Status status = update.getStatus();
            if (status != RemoteRefUpdate.Status.OK
                    && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                throw new IllegalStateException(
                        "JGit push update failed for " + update.getRemoteName() + ": " + status);
            }
        }
    }
}
