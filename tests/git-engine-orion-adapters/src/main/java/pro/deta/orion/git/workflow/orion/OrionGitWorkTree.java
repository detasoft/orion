package pro.deta.orion.git.workflow.orion;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.pack.PackIngestionOutput;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.fetch.FetchPack;
import pro.deta.orion.git.parser.v2.fetch.FetchPlan;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectGraph;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitOperationResult;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitScenarioContext;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

final class OrionGitWorkTree implements GitWorkTree {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_REF = "refs/heads/" + GitScenarioContext.DEFAULT_BRANCH;
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
        Files.createDirectories(gitDirectory);
        GitStorageApi storage = new GitStorageApi(gitDirectory);
        storage.updateHead(new Head.Symbolic(new RefId(MAIN_REF)));
        NativeGitRepository repository = new NativeGitRepository(
                workTree.getFileName().toString(), storage, MAIN_REF);
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
    public RepositorySnapshot snapshot() throws Exception {
        Map<String, String> refs = repository.refs();
        Set<ObjectId> roots = new LinkedHashSet<>();
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            if (ref.getKey().startsWith("refs/heads/") || ref.getKey().startsWith("refs/tags/")) {
                roots.add(new ObjectId(ref.getValue()));
            }
        }
        FetchPlan plan = new FetchPlan(roots, Map.of(), Set.of(), Set.of(), OptionalInt.empty(),
                OptionalLong.empty(), Set.of(), Optional.empty(), new GitCapabilities(), Set.of());
        FetchPack pack = FetchPack.prepare(repository.storage(), plan);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), pack.objectCount())) {
            pack.writeTo(writer);
            writer.finish();
        }
        try (InMemoryRepository observer = new InMemoryRepository(new DfsRepositoryDescription());
             ObjectInserter inserter = observer.newObjectInserter()) {
            inserter.newPackParser(new ByteArrayInputStream(bytes.toByteArray())).parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
            for (Map.Entry<String, String> ref : refs.entrySet()) {
                org.eclipse.jgit.lib.RefUpdate update = observer.updateRef(ref.getKey());
                update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(ref.getValue()));
                update.forceUpdate();
            }
            observer.updateRef("HEAD").link("refs/heads/" + currentBranch);
            return RepositorySnapshot.capture(observer);
        }
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
        Map<String, GitFile> files = new LinkedHashMap<>();
        Set<String> deletedPaths = new LinkedHashSet<>();
        for (String path : stagedPaths) {
            Path source = directory.resolve(path);
            if (Files.isSymbolicLink(source)) {
                files.put(path, new GitFile(FileMode.SYMLINK,
                        Files.readSymbolicLink(source).toString().getBytes(StandardCharsets.UTF_8)));
                continue;
            }
            boolean deleted = Files.notExists(source) || Files.isDirectory(source);
            for (Path parent = source.getParent(); !deleted && parent != null && parent.startsWith(directory);
                 parent = parent.getParent()) {
                deleted = Files.isRegularFile(parent);
            }
            if (deleted) {
                try {
                    repository.loadFiles(currentBranch, List.of(path));
                } catch (GitOperationException failure) {
                    throw new IOException("Staged Git path is not tracked: " + path, failure);
                }
                deletedPaths.add(path);
                continue;
            }
            if (!Files.isRegularFile(source)) {
                throw new IOException("Staged Git path is not a regular file: " + path);
            }
            FileMode mode = Files.isExecutable(source) ? FileMode.EXECUTABLE_FILE : FileMode.REGULAR_FILE;
            files.put(path, new GitFile(mode, Files.readAllBytes(source)));
        }
        repository.saveFiles(currentBranch, files, deletedPaths, message, PARITY_AUTHOR);
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
        Set<ObjectId> wants = new LinkedHashSet<>();
        for (String refSpec : refSpecs) {
            RefSpec parsed = RefSpec.parse(refSpec);
            boolean delete = parsed.source().isEmpty();
            String newId = delete ? NULL_ID : resolve(parsed.source());
            String oldId = advertisement.findRef(parsed.destination())
                    .map(GitRemoteAdvertisement.Ref::objectId)
                    .orElse(null);
            if (!delete && !parsed.force() && !isFastForward(parsed.destination(), oldId, newId)) {
                return GitOperationResult.nonFastForward(
                        "Orion push rejected a non-fast-forward update for " + parsed.destination());
            }
            commands.add(new GitReceivePackRequest.Command(
                    oldId == null ? NULL_ID : oldId,
                    newId,
                    parsed.destination()));
            if (!delete) {
                wants.add(new ObjectId(newId));
            }
        }
        GitReceivePackRequest request = new GitReceivePackRequest(
                commands,
                output -> {
                    if (wants.isEmpty()) {
                        return;
                    }
                    FetchPlan plan = new FetchPlan(wants, Map.of(), Set.of(), Set.of(), OptionalInt.empty(),
                            OptionalLong.empty(), Set.of(), Optional.empty(), new GitCapabilities(), Set.of());
                    FetchPack pack = FetchPack.prepare(repository.storage(), plan);
                    try (PackWriter writer = new PackWriter(output, pack.objectCount())) {
                        pack.writeTo(writer);
                        writer.finish();
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
        if (result.status() != RefUpdateResult.Status.APPLIED) {
            throw new IllegalStateException("Orion local ref update was stale: " + refName);
        }
    }

    @Override
    public String annotatedTag(String name, String target) throws Exception {
        ObjectId targetId = new ObjectId(target);
        String type = repository.readObject(targetId).orElseThrow().type().name().toLowerCase(Locale.ROOT);
        byte[] content = ("object " + target + "\ntype " + type + "\ntag " + name
                + "\ntagger Parity <parity@example.test> 0 +0000\n\n" + name + "\n")
                .getBytes(StandardCharsets.UTF_8);
        ObjectId tag = repository.writeObject(GitObjectType.TAG, content);
        updateRef("refs/tags/" + name, tag.toHex());
        return tag.toHex();
    }

    @Override
    public Map<String, String> advertisedRefs(String remoteName) {
        GitRemoteRepository remote = remote(remoteName);
        GitRemoteAdvertisement advertisement = OrionGitClient.requireSuccess(
                client.uploadPack().discover(client.uri(remote), client.options()), "upload-pack discovery");
        Map<String, String> refs = new LinkedHashMap<>();
        for (GitRemoteAdvertisement.Ref ref : advertisement.refs()) {
            refs.put(ref.name(), ref.objectId());
            ref.peeledObjectId().ifPresent(id -> refs.put(ref.name() + "^{}", id));
        }
        return refs;
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
        if (localId != null && !new GitObjectGraph(repository.storage()).isAncestor(new ObjectId(localId), new ObjectId(remoteId))) {
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
        repository.storage().updateHead(new Head.Symbolic(new RefId(refName)));
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

    boolean fetchBranch(String remoteName, String branch) throws Exception {
        GitRemoteRepository remote = remote(remoteName);
        GitRemoteAdvertisement advertisement = OrionGitClient.requireSuccess(
                client.uploadPack().discover(client.uri(remote), client.options()),
                "upload-pack discovery");
        if (advertisement.refs().isEmpty()) {
            return false;
        }
        String remoteRef = "refs/heads/" + branch;
        String wantedId = advertisement.findRef(remoteRef)
                .map(GitRemoteAdvertisement.Ref::objectId)
                .orElseThrow(() -> new IllegalStateException(
                        "Remote Git ref is missing: " + remoteRef));
        Set<ObjectId> haves = new LinkedHashSet<>();
        for (String objectId : repository.refs().values()) {
            haves.add(new ObjectId(objectId));
        }
        try (PackIngestionOutput target = new PackIngestionOutput(
                repository.storage())) {
            GitUploadPackRequest request = new GitUploadPackRequest(
                    List.of(wantedId),
                    haves.stream().map(ObjectId::toHex).toList(),
                    target,
                    ignored -> { });
            OrionGitClient.requireSuccess(
                    client.uploadPack().fetch(client.uri(remote), client.options(), request),
                    "upload-pack");
            repository.storage().persist(target.complete());
            String localTrackingRef = trackingRef(remoteName, branch);
            String oldId = repository.refs().getOrDefault(localTrackingRef, NULL_ID);
            List<RefUpdateResult> results = repository.publishRefs(
                    List.of(RefUpdate.fromWire(localTrackingRef, oldId, wantedId)), true);
            if (results.getFirst().status() != RefUpdateResult.Status.APPLIED) {
                throw new IllegalStateException("Orion fetch ref update was stale: " + localTrackingRef);
            }
        }
        return true;
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
            ObjectId candidate = new ObjectId(target);
            if (repository.readObject(candidate).isPresent()) {
                objectId = target;
            }
        }
        if (objectId == null) {
            throw new IllegalArgumentException("Cannot resolve Git ref target: " + target);
        }
        return objectId;
    }

    private boolean isFastForward(String refName, String oldId, String newId) throws IOException {
        if (oldId == null || oldId.equals(newId) || !refName.startsWith("refs/heads/")) {
            return true;
        }
        return new GitObjectGraph(repository.storage()).isAncestor(new ObjectId(oldId), new ObjectId(newId));
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

    private record RefSpec(String source, String destination, boolean force) {
        private static RefSpec parse(String value) {
            Objects.requireNonNull(value, "refSpec");
            boolean force = value.startsWith("+");
            int sourceStart = force ? 1 : 0;
            int separator = value.indexOf(':');
            if (separator < sourceStart || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
                throw new UnsupportedOperationException(
                        "Orion adapter requires a source:destination or :destination refspec: " + value);
            }
            return new RefSpec(value.substring(sourceStart, separator), value.substring(separator + 1), force);
        }
    }
}
