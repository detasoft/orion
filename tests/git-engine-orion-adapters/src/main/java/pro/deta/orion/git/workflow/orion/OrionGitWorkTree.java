package pro.deta.orion.git.workflow.orion;

import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionOutput;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore.Update;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.NativeFetchOptions;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeObjectClosure;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitOperationResult;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitScenarioContext;
import pro.deta.orion.git.workflow.GitWorkTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class OrionGitWorkTree implements GitWorkTree {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_REF = "refs/heads/" + GitScenarioContext.DEFAULT_BRANCH;
    private static final PackIngestionLimits PACK_LIMITS = new PackIngestionLimits(
            100L * 1024 * 1024,
            1_000_000,
            64 * 1024 * 1024);
    private static final GitCommitAuthor PARITY_AUTHOR = new GitCommitAuthor(
            GitScenarioContext.IDENTITY_NAME,
            GitScenarioContext.IDENTITY_EMAIL);

    private final OrionGitClient client;
    private final Path directory;
    private final NativeGitRepository repository;
    private final Set<String> stagedPaths = new LinkedHashSet<>();
    private final Map<String, GitRemoteRepository> remotes = new LinkedHashMap<>();
    private String currentBranch = GitScenarioContext.DEFAULT_BRANCH;

    private OrionGitWorkTree(
            OrionGitClient client,
            Path directory,
            NativeGitRepository repository) {
        this.client = Objects.requireNonNull(client, "client");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    static OrionGitWorkTree create(OrionGitClient client, Path directory) throws IOException {
        Path workTree = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Path gitDirectory = workTree.resolve(".git");
        Files.createDirectories(gitDirectory.resolve("objects"));
        Files.createDirectories(gitDirectory.resolve("refs"));
        Files.writeString(
                gitDirectory.resolve("HEAD"),
                "ref: " + MAIN_REF + "\n",
                StandardCharsets.US_ASCII);
        NativeGitRepository repository = new NativeGitRepository(
                workTree.getFileName().toString(),
                new LooseRefStore(gitDirectory),
                new LooseObjectStore(gitDirectory.resolve("objects")),
                MAIN_REF);
        return new OrionGitWorkTree(client, workTree, repository);
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
    public void add(String... pathspecs) {
        Objects.requireNonNull(pathspecs, "pathspecs");
        for (String pathspec : pathspecs) {
            stagedPaths.add(requireFilePath(pathspec));
        }
    }

    @Override
    public void commit(String message) throws Exception {
        if (stagedPaths.isEmpty()) {
            throw new IllegalStateException("Orion native commit requires staged files");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String path : stagedPaths) {
            Path source = directory.resolve(path);
            if (!Files.isRegularFile(source)) {
                throw new IOException("Staged Git path is not a regular file: " + path);
            }
            files.put(path, Files.readAllBytes(source));
        }
        repository.saveFiles(currentBranch, files, message, PARITY_AUTHOR);
        stagedPaths.clear();
    }

    @Override
    public void addRemote(String name, GitRemoteRepository remote) {
        String checkedName = Objects.requireNonNull(name, "name");
        if (checkedName.isBlank()) {
            throw new IllegalArgumentException("Remote name must not be blank");
        }
        if (remotes.putIfAbsent(checkedName, Objects.requireNonNull(remote, "remote")) != null) {
            throw new IllegalStateException("Remote already exists: " + checkedName);
        }
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
    public void pushRefs(String remoteName, String... refSpecs) throws Exception {
        GitOperationResult result = pushRefsResult(remoteName, refSpecs);
        if (!result.isAccepted()) {
            throw new IllegalStateException(result.diagnostic());
        }
    }

    private GitOperationResult pushRefsResult(String remoteName, String... refSpecs) throws Exception {
        GitRemoteRepository remote = remote(remoteName);
        GitRemoteAdvertisement advertisement = OrionGitClient.requireSuccess(
                client.receivePack().discover(client.uri(remote), client.options()),
                "receive-pack discovery");
        List<GitReceivePackRequest.Command> commands = new ArrayList<>();
        Set<GitObjectId> wants = new LinkedHashSet<>();
        for (String refSpec : refSpecs) {
            RefSpec parsed = RefSpec.parse(refSpec);
            String newId = resolve(parsed.source());
            String oldId = advertisement.findRef(parsed.destination())
                    .map(GitRemoteAdvertisement.Ref::objectId)
                    .orElse(null);
            if (!isFastForward(parsed.destination(), oldId, newId)) {
                return GitOperationResult.nonFastForward(
                        "Orion push rejected a non-fast-forward update for " + parsed.destination());
            }
            commands.add(new GitReceivePackRequest.Command(
                    oldId == null ? NULL_ID : oldId,
                    newId,
                    parsed.destination()));
            wants.add(GitObjectId.of(newId));
        }
        NativeFetchRequest packRequest = new NativeFetchRequest(
                wants,
                Set.of(),
                true,
                Set.of(),
                NativeFetchOptions.DEFAULT);
        GitReceivePackRequest request = new GitReceivePackRequest(
                commands,
                output -> {
                    try (NativePackProducer producer = repository.fetch(packRequest)) {
                        producer.writeTo(output);
                    }
                });
        OrionGitClient.requireAccepted(OrionGitClient.requireSuccess(
                client.receivePack().push(client.uri(remote), client.options(), request),
                "receive-pack"));
        return GitOperationResult.accepted();
    }

    @Override
    public void updateRef(String refName, String target) {
        String objectId = resolve(target);
        String oldId = repository.refs().getOrDefault(refName, NULL_ID);
        RefUpdateResult result = repository.updateRef(refName, oldId, objectId);
        if (result == RefUpdateResult.STALE) {
            throw new IllegalStateException("Orion local ref update was stale: " + refName);
        }
    }

    @Override
    public void fetch(String remote) throws Exception {
        fetchBranch(remote, GitScenarioContext.DEFAULT_BRANCH);
    }

    @Override
    public void fetch(String remote, String branch) throws Exception {
        fetchBranch(remote, branch);
    }

    @Override
    public void pull(String remote, String branch) throws Exception {
        fetchBranch(remote, branch);
        String localRef = "refs/heads/" + branch;
        String trackingRef = trackingRef(remote, branch);
        String remoteId = requireRef(trackingRef);
        String localId = repository.refs().get(localRef);
        if (localId != null && !new NativeObjectClosure(repository::readObject).allRootsReachAny(
                List.of(GitObjectId.of(remoteId)),
                List.of(GitObjectId.of(localId)))) {
            throw new IllegalStateException("Orion pull is not a fast-forward for " + localRef);
        }
        updateRef(localRef, trackingRef);
    }

    @Override
    public String head() {
        String objectId = repository.refs().get("refs/heads/" + currentBranch);
        if (objectId == null) {
            throw new IllegalStateException("Orion native HEAD is unborn");
        }
        return objectId;
    }

    @Override
    public void checkout(String branch, String startPoint) throws Exception {
        String refName = "refs/heads/" + branch;
        if (!repository.refs().containsKey(refName)) {
            updateRef(refName, startPoint);
        }
        currentBranch = branch;
        Files.writeString(
                directory.resolve(".git/HEAD"),
                "ref: " + refName + "\n",
                StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        repository.close();
    }

    GitRemoteRepository remote(String name) {
        GitRemoteRepository remote = remotes.get(name);
        if (remote == null) {
            throw new IllegalArgumentException("Unknown Git remote: " + name);
        }
        return remote;
    }

    private void fetchBranch(String remoteName, String branch) throws Exception {
        GitRemoteRepository remote = remote(remoteName);
        GitRemoteAdvertisement advertisement = OrionGitClient.requireSuccess(
                client.uploadPack().discover(client.uri(remote), client.options()),
                "upload-pack discovery");
        String remoteRef = "refs/heads/" + branch;
        String wantedId = advertisement.findRef(remoteRef)
                .map(GitRemoteAdvertisement.Ref::objectId)
                .orElseThrow(() -> new IllegalStateException(
                        "Remote Git ref is missing: " + remoteRef));
        Set<GitObjectId> haves = new LinkedHashSet<>();
        for (String objectId : repository.refs().values()) {
            haves.add(GitObjectId.of(objectId));
        }
        try (PackIngestionOutput target = new PackIngestionOutput(
                repository.beginPackIngestion(PACK_LIMITS))) {
            GitUploadPackRequest request = new GitUploadPackRequest(
                    List.of(wantedId),
                    haves.stream().map(GitObjectId::value).toList(),
                    target,
                    ignored -> { });
            OrionGitClient.requireSuccess(
                    client.uploadPack().fetch(client.uri(remote), client.options(), request),
                    "upload-pack");
            LooseObjectStore quarantine = target.complete().quarantine();
            String localTrackingRef = trackingRef(remoteName, branch);
            String oldId = repository.refs().getOrDefault(localTrackingRef, NULL_ID);
            List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                    quarantine,
                    List.of(new Update(localTrackingRef, oldId, wantedId)));
            if (results.contains(RefUpdateResult.STALE)) {
                throw new IllegalStateException("Orion fetch ref update was stale: " + localTrackingRef);
            }
        }
    }

    private String requireRef(String refName) {
        String value = repository.refs().get(refName);
        if (value == null) {
            throw new IllegalStateException("Git ref is missing: " + refName);
        }
        return value;
    }

    private static String trackingRef(String remote, String branch) {
        return "refs/remotes/" + remote + "/" + branch;
    }

    private String resolve(String target) {
        Objects.requireNonNull(target, "target");
        String objectId = "HEAD".equals(target)
                ? repository.refs().get("refs/heads/" + currentBranch)
                : repository.refs().get(target);
        if (objectId == null && target.length() == 40) {
            GitObjectId candidate = GitObjectId.of(target);
            if (repository.readObject(candidate).isPresent()) {
                objectId = target;
            }
        }
        if (objectId == null) {
            throw new IllegalArgumentException("Cannot resolve Git ref target: " + target);
        }
        return objectId;
    }

    private boolean isFastForward(String refName, String oldId, String newId) {
        if (oldId == null || oldId.equals(newId) || !refName.startsWith("refs/heads/")) {
            return true;
        }
        return new NativeObjectClosure(repository::readObject).allRootsReachAny(
                List.of(GitObjectId.of(newId)),
                List.of(GitObjectId.of(oldId)));
    }

    private static String requireFilePath(String pathspec) {
        Objects.requireNonNull(pathspec, "pathspec");
        Path path = Path.of(pathspec);
        if (path.isAbsolute() || path.getNameCount() == 0) {
            throw new IllegalArgumentException("Git path must be a relative file: " + pathspec);
        }
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("Git path must not escape the worktree: " + pathspec);
            }
        }
        String normalized = path.normalize().toString().replace('\\', '/');
        if (normalized.isBlank() || ".".equals(normalized)) {
            throw new IllegalArgumentException("Git path must be a relative file: " + pathspec);
        }
        return normalized;
    }

    private record RefSpec(String source, String destination) {
        private static RefSpec parse(String value) {
            Objects.requireNonNull(value, "refSpec");
            if (value.startsWith("+")) {
                throw new UnsupportedOperationException(
                        "Orion adapter does not support forced refspecs: " + value);
            }
            int separator = value.indexOf(':');
            if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
                throw new UnsupportedOperationException(
                        "Orion adapter requires an explicit source:destination refspec: " + value);
            }
            return new RefSpec(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
