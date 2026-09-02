package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
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
    public String diagnostics() {
        return JGitDiagnostics.version();
    }

    @Override
    public GitWorkTree init(Path directory) throws Exception {
        Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch(GitScenarioContext.DEFAULT_BRANCH)
                .call();
        configure(git);
        return new JGitWorkTree(this, directory, git);
    }

    @Override
    public GitWorkTree clone(String remoteUri, Path directory) throws Exception {
        Git git = null;
        try {
            git = Git.cloneRepository()
                    .setURI(remoteUri)
                    .setDirectory(directory.toFile())
                    .setNoCheckout(true)
                    .call();
            configure(git);
            git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef("refs/heads/" + GitScenarioContext.DEFAULT_BRANCH)
                    .call();
            return new JGitWorkTree(this, directory, git);
        } catch (Exception | Error failure) {
            if (git != null) {
                git.close();
            }
            throw failure;
        }
    }

    private static void configure(Git git) throws Exception {
        StoredConfig config = git.getRepository().getConfig();
        config.setBoolean("commit", null, "gpgSign", false);
        config.setBoolean("core", null, "autocrlf", false);
        config.setBoolean("core", null, "fileMode", false);
        config.save();
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
                    .setMessage(message.stripTrailing() + "\n")
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
            pushRefs(remote, "refs/heads/" + branch + ":refs/heads/" + branch);
        }

        @Override
        public void pushRefs(String remote, String... refSpecs) throws Exception {
            RefSpec[] specs = new RefSpec[refSpecs.length];
            for (int index = 0; index < refSpecs.length; index++) {
                specs[index] = new RefSpec(refSpecs[index]);
            }
            var results = git.push().setRemote(remote).setRefSpecs(specs).call();
            for (var result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    requireAcceptedPush(update);
                }
            }
        }

        @Override
        public void updateRef(String refName, String target) throws Exception {
            ObjectId objectId = git.getRepository().resolve(target);
            if (objectId == null) {
                throw new IllegalArgumentException("Cannot resolve Git ref target: " + target);
            }
            RefUpdate update = git.getRepository().updateRef(refName);
            update.setNewObjectId(objectId);
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.FORCED
                    && result != RefUpdate.Result.NO_CHANGE) {
                throw new IllegalStateException("JGit ref update failed for " + refName + ": " + result);
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
                    .setRebase(false)
                    .setFastForward(FastForwardMode.FF_ONLY)
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
