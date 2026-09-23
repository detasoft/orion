package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

final class JGitWorkflowClient implements GitClient {
    private static final PersonIdent PARITY_IDENTITY = new PersonIdent(
            GitScenarioContext.IDENTITY_NAME,
            GitScenarioContext.IDENTITY_EMAIL,
            Date.from(GitScenarioContext.COMMIT_TIME),
            TimeZone.getTimeZone("UTC"));

    private final String name;
    private final TransportConfigCallback transportConfig;

    JGitWorkflowClient(String name) {
        this(name, transport -> { });
    }

    JGitWorkflowClient(String name, TransportConfigCallback transportConfig) {
        this.name = Objects.requireNonNull(name, "name");
        this.transportConfig = transportConfig;
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
                    .setTransportConfigCallback(transportConfig)
                    .setDirectory(directory.toFile())
                    .setNoCheckout(true)
                    .call();
            configure(git);
            if (git.getRepository().getRefDatabase().getRefsByPrefix("refs/").isEmpty()) {
                git.getRepository().updateRef("HEAD").link("refs/heads/" + GitScenarioContext.DEFAULT_BRANCH);
            } else {
                git.reset()
                        .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef("refs/heads/" + GitScenarioContext.DEFAULT_BRANCH)
                        .call();
            }
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
        private final JGitWorkflowClient client;
        private final Path directory;
        private final Git git;

        private JGitWorkTree(JGitWorkflowClient client, Path directory, Git git) {
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
        public GitOperationResult pushResult(String remote, String branch) throws Exception {
            return pushRefsResult(remote, "refs/heads/" + branch + ":refs/heads/" + branch);
        }

        @Override
        public void pushRefs(String remote, String... refSpecs) throws Exception {
            GitOperationResult result = pushRefsResult(remote, refSpecs);
            if (!result.isAccepted()) {
                throw new IllegalStateException("JGit push failed: " + result.status());
            }
        }

        private GitOperationResult pushRefsResult(String remote, String... refSpecs) throws Exception {
            RefSpec[] specs = new RefSpec[refSpecs.length];
            for (int index = 0; index < refSpecs.length; index++) {
                specs[index] = new RefSpec(refSpecs[index]);
            }
            Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
                    .setTransportConfigCallback(client.transportConfig).setRemote(remote).setRefSpecs(specs).call();
            for (var result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (update.getStatus() == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                        return GitOperationResult.nonFastForward("remote rejected non-fast-forward update");
                    }
                    if (!accepted(update.getStatus())) {
                        return GitOperationResult.rejected("remote rejected ref update: " + update.getStatus());
                    }
                }
            }
            return GitOperationResult.accepted();
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
        public String annotatedTag(String name, String target) throws Exception {
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                return git.tag().setName(name).setAnnotated(true).setMessage(name).setTagger(PARITY_IDENTITY)
                        .setObjectId(walk.parseAny(ObjectId.fromString(target))).call().getObjectId().name();
            }
        }

        @Override
        public Map<String, String> advertisedRefs(String remote) throws Exception {
            StoredConfig config = git.getRepository().getConfig();
            String previous = config.getString("protocol", null, "version");
            config.setString("protocol", null, "version", "1");
            try (Transport transport = Transport.open(git.getRepository(), remote)) {
                client.transportConfig.configure(transport);
                try (FetchConnection connection = transport.openFetch()) {
                    Map<String, String> refs = new LinkedHashMap<>();
                    for (Ref ref : connection.getRefs()) {
                        refs.put(ref.getName(), ref.getObjectId().name());
                        if (ref.getPeeledObjectId() != null) {
                            refs.put(ref.getName() + "^{}", ref.getPeeledObjectId().name());
                        }
                    }
                    return refs;
                }
            } finally {
                if (previous == null) {
                    config.unset("protocol", null, "version");
                } else {
                    config.setString("protocol", null, "version", previous);
                }
            }
        }

        @Override
        public void fetch(String remote) throws Exception {
            git.fetch().setTransportConfigCallback(client.transportConfig).setRemote(remote).call();
        }

        @Override
        public void fetch(String remote, String branch) throws Exception {
            git.fetch()
                    .setTransportConfigCallback(client.transportConfig)
                    .setRemote(remote)
                    .setRefSpecs(new RefSpec("+refs/heads/" + branch
                            + ":refs/remotes/" + remote + "/" + branch))
                    .call();
        }

        @Override
        public void pull(String remote, String branch) throws Exception {
            PullResult result = git.pull()
                    .setTransportConfigCallback(client.transportConfig)
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
        public void checkout(String branch, String startPoint) throws Exception {
            git.checkout().setCreateBranch(true).setName(branch).setStartPoint(startPoint).call();
        }

        @Override
        public String head() throws Exception {
            return git.getRepository().resolve("HEAD").name();
        }

        @Override
        public void close() {
            git.close();
        }

        private static boolean accepted(RemoteRefUpdate.Status status) {
            return status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE;
        }
    }
}
