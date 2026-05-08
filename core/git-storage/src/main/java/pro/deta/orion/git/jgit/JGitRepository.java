package pro.deta.orion.git.jgit;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.NameRevCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.pack.PackStatistics;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PostUploadHook;
import org.eclipse.jgit.transport.PreUploadHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import pro.deta.orion.git.common.GitCommitAuthor;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.common.GitRepositoryFileSnapshot;
import pro.deta.orion.git.common.GitRefUpdate;
import pro.deta.orion.git.common.GitRefUpdateResult;
import pro.deta.orion.git.common.GitRefUpdateType;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.common.GitUploadStats;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Transitional Orion repository implementation backed by JGit. It keeps JGit behind the GitRepository interface while
 * protocol adapters are still implemented with JGit classes.
 */
@Slf4j
public final class JGitRepository implements GitRepository {
    private final String name;
    private final Repository repository;
    private final Consumer<GitFetchAccessRequest> fetchAccessCheck;

    public JGitRepository(String name, Repository repository) {
        this(name, repository, ignored -> {
        });
    }

    public static JGitRepository open(String name, Path repositoryPath, boolean createIfMissing) throws IOException, GitRepositoryFileNotFoundException {
        Repository repository = openRepository(repositoryPath, createIfMissing);
        try {
            return new JGitRepository(name, repository);
        } catch (RuntimeException e) {
            repository.close();
            throw e;
        }
    }

    private JGitRepository(String name, Repository repository, Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        this.name = Objects.requireNonNull(name, "name");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fetchAccessCheck = Objects.requireNonNull(fetchAccessCheck, "fetchAccessCheck");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        File directory = repository.getDirectory();
        if (directory != null) {
            return directory.toString();
        }
        return repository.getIdentifier();
    }

    @Override
    public GitRepository withFetchAccessCheck(Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        return new JGitRepository(name, repository, fetchAccessCheck);
    }

    @Override
    public void upload(GitUploadRequest request, InputStream input, OutputStream output, OutputStream error) throws IOException, GitOperationException {
        Objects.requireNonNull(request, "request");
        UploadPack uploadPack = new UploadPack(repository);
        uploadPack.setTimeout(request.timeoutSeconds());
        uploadPack.setExtraParameters(request.extraParameters());
        uploadPack.setPostUploadHook(new PostUploadHook() {
            @Override
            public void onPostUpload(PackStatistics stats) {
                request.afterUpload().accept(toGitUploadStats(stats));
            }
        });
        uploadPack.setPreUploadHook(new PreUploadHook() {
            @Override
            public void onBeginNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntOffered) throws ServiceMayNotContinueException {
                requireFetchAccess(up, wants);
            }

            @Override
            public void onEndNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntCommon, int cntNotFound, boolean ready) {
                log.trace("onEndNegotiateRound");
            }

            @Override
            public void onSendPack(UploadPack up, Collection<? extends ObjectId> wants, Collection<? extends ObjectId> haves) {
                log.trace("onSendPack {} {}", wants, haves);
            }
        });
        try {
            uploadPack.uploadWithExceptionPropagation(input, output, error);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void receive(GitReceiveRequest request, InputStream input, OutputStream output, OutputStream error) throws IOException {
        Objects.requireNonNull(request, "request");
        ReceivePack receivePack = new ReceivePack(repository);
        receivePack.setTimeout(request.timeoutSeconds());
        receivePack.setPostReceiveHook(new PostReceiveHook() {
            @Override
            public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
                List<ReceiveCommand> eventCommands = rp.getAllCommands();
                if (!eventCommands.isEmpty()) {
                    List<GitRefUpdate> updates = new ArrayList<>();
                    for (ReceiveCommand command : eventCommands) {
                        updates.add(toGitRefUpdate(command));
                    }
                    request.afterReceive().accept(updates);
                }
            }
        });
        receivePack.receive(input, output, error);
    }

    @Override
    public GitRepositoryFileSnapshot loadFiles(String branch, List<String> paths) throws IOException, GitOperationException {
        Objects.requireNonNull(paths, "paths");
        try (RevWalk revWalk = new RevWalk(repository)) {
            ObjectId branchId = resolveBranch(repository, branch);
            if (branchId == null) {
                throw new GitRepositoryFileNotFoundException("Branch not found: " + branch);
            }

            RevCommit commit = revWalk.parseCommit(branchId);
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String path : paths) {
                String gitPath = gitPath(path);
                try (TreeWalk treeWalk = TreeWalk.forPath(repository, gitPath, commit.getTree())) {
                    if (treeWalk == null) {
                        throw new GitRepositoryFileNotFoundException("File not found: " + gitPath);
                    }
                    files.put(gitPath, repository.open(treeWalk.getObjectId(0)).getBytes());
                }
            }
            return new GitRepositoryFileSnapshot(files, Optional.of(commit.name()));
        }
    }

    @Override
    public void saveFiles(String branch, Map<String, byte[]> files, String message, GitCommitAuthor author) throws IOException {
        Objects.requireNonNull(files, "files");
        String branchRefName = branchRefName(branch);
        try (RevWalk revWalk = new RevWalk(repository);
             ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId oldBranchId = resolveBranch(repository, branch);
            RevCommit parent = oldBranchId == null ? null : revWalk.parseCommit(oldBranchId);

            TreeMap<String, TreeEntry> treeEntries = new TreeMap<>();
            if (parent != null) {
                readTreeEntries(repository, parent, treeEntries);
            }

            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String path = gitPath(entry.getKey());
                ObjectId blobId = inserter.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, entry.getValue());
                treeEntries.put(path, new TreeEntry(FileMode.REGULAR_FILE, blobId));
            }

            ObjectId treeId = writeTree(treeEntries, inserter);
            ObjectId commitId = writeCommit(inserter, treeId, parent, message, author);
            inserter.flush();
            updateBranch(repository, branchRefName, oldBranchId, commitId);
        }
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> repositoryType) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (repositoryType.isInstance(repository)) {
            return Optional.of(repositoryType.cast(repository));
        }
        if (repositoryType.isInstance(this)) {
            return Optional.of(repositoryType.cast(this));
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        repository.close();
    }

    private void requireFetchAccess(UploadPack uploadPack, Collection<? extends ObjectId> wants) throws ServiceMayNotContinueException {
        try {
            fetchAccessCheck.accept(new GitFetchAccessRequest(
                    name,
                    toGitObjectIds(wants),
                    objectIds -> resolveBranchNames(uploadPack.getRepository(), objectIds)));
        } catch (RuntimeException e) {
            throw new ServiceMayNotContinueException(e.getMessage(), e);
        }
    }

    private static GitRefUpdate toGitRefUpdate(ReceiveCommand command) {
        return new GitRefUpdate(
                command.getRefName(),
                toGitObjectId(command.getOldId()),
                toGitObjectId(command.getNewId()),
                toGitRefUpdateType(command.getType()),
                toGitRefUpdateResult(command.getResult()));
    }

    private static GitObjectId toGitObjectId(ObjectId objectId) {
        if (objectId == null) {
            return null;
        }
        return GitObjectId.of(objectId.name());
    }

    private static GitRefUpdateType toGitRefUpdateType(ReceiveCommand.Type type) {
        return switch (type) {
            case CREATE -> GitRefUpdateType.CREATE;
            case UPDATE -> GitRefUpdateType.UPDATE;
            case UPDATE_NONFASTFORWARD -> GitRefUpdateType.UPDATE_NON_FAST_FORWARD;
            case DELETE -> GitRefUpdateType.DELETE;
        };
    }

    private static GitRefUpdateResult toGitRefUpdateResult(ReceiveCommand.Result result) {
        return switch (result) {
            case NOT_ATTEMPTED -> GitRefUpdateResult.NOT_ATTEMPTED;
            case OK -> GitRefUpdateResult.OK;
            case REJECTED_NOCREATE -> GitRefUpdateResult.REJECTED_NO_CREATE;
            case REJECTED_NODELETE -> GitRefUpdateResult.REJECTED_NO_DELETE;
            case REJECTED_NONFASTFORWARD -> GitRefUpdateResult.REJECTED_NON_FAST_FORWARD;
            case REJECTED_CURRENT_BRANCH -> GitRefUpdateResult.REJECTED_CURRENT_BRANCH;
            case REJECTED_MISSING_OBJECT -> GitRefUpdateResult.REJECTED_MISSING_OBJECT;
            case REJECTED_OTHER_REASON -> GitRefUpdateResult.REJECTED_OTHER_REASON;
            case LOCK_FAILURE -> GitRefUpdateResult.LOCK_FAILURE;
        };
    }

    private static GitUploadStats toGitUploadStats(PackStatistics stats) {
        return new GitUploadStats(stats.getTotalObjects(), stats.getReusedObjects(), stats.getTotalBytes());
    }

    private static List<GitObjectId> toGitObjectIds(Collection<? extends ObjectId> objectIds) {
        List<GitObjectId> result = new ArrayList<>();
        for (ObjectId objectId : objectIds) {
            result.add(toGitObjectId(objectId));
        }
        return result;
    }

    private static Map<GitObjectId, String> resolveBranchNames(Repository repository, Collection<GitObjectId> objectIds) {
        NameRevCommand nameRev = new Git(repository).nameRev().addPrefix("refs/heads");
        for (GitObjectId objectId : objectIds) {
            try {
                nameRev.add(ObjectId.fromString(objectId.value()));
            } catch (MissingObjectException e) {
                log.error("Can't find commit {}", objectId.value());
                return Collections.emptyMap();
            }
        }

        Map<ObjectId, String> branchNames;
        try {
            branchNames = nameRev.call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }

        Map<GitObjectId, String> result = new LinkedHashMap<>();
        for (Map.Entry<ObjectId, String> entry : branchNames.entrySet()) {
            result.put(toGitObjectId(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Repository openRepository(Path repositoryPath, boolean createIfMissing) throws IOException, GitRepositoryFileNotFoundException {
        Path normalizedRepositoryPath = Objects.requireNonNull(repositoryPath, "repositoryPath").toAbsolutePath().normalize();
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Path worktreeGitDir = normalizedRepositoryPath.resolve(".git");
        Repository repository;
        if (Files.isDirectory(worktreeGitDir)) {
            repository = builder.setGitDir(worktreeGitDir.toFile()).build();
        } else {
            repository = builder.setBare().setGitDir(normalizedRepositoryPath.toFile()).build();
        }
        if (!repository.getObjectDatabase().exists()) {
            if (!createIfMissing) {
                repository.close();
                throw new GitRepositoryFileNotFoundException("Repository not found: " + normalizedRepositoryPath);
            }
            repository.create(repository.isBare());
        }
        return repository;
    }

    private static ObjectId resolveBranch(Repository repository, String branch) throws IOException {
        ObjectId objectId = repository.resolve(branchRefName(branch));
        if (objectId == null && !branch.startsWith(org.eclipse.jgit.lib.Constants.R_REFS)) {
            objectId = repository.resolve(branch);
        }
        return objectId;
    }

    private static String branchRefName(String branch) {
        Objects.requireNonNull(branch, "branch");
        if (branch.startsWith(org.eclipse.jgit.lib.Constants.R_REFS)) {
            return branch;
        }
        return org.eclipse.jgit.lib.Constants.R_HEADS + branch;
    }

    private static void readTreeEntries(Repository repository, RevCommit commit, TreeMap<String, TreeEntry> entries) throws IOException {
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                entries.put(treeWalk.getPathString(), new TreeEntry(treeWalk.getFileMode(0), treeWalk.getObjectId(0)));
            }
        }
    }

    private static ObjectId writeTree(TreeMap<String, TreeEntry> entries, ObjectInserter inserter) throws IOException {
        DirCache dirCache = DirCache.newInCore();
        DirCacheBuilder builder = dirCache.builder();
        for (Map.Entry<String, TreeEntry> entry : entries.entrySet()) {
            DirCacheEntry cacheEntry = new DirCacheEntry(entry.getKey());
            cacheEntry.setFileMode(entry.getValue().fileMode());
            cacheEntry.setObjectId(entry.getValue().objectId());
            builder.add(cacheEntry);
        }
        builder.finish();
        return dirCache.writeTree(inserter);
    }

    private static ObjectId writeCommit(ObjectInserter inserter, ObjectId treeId, RevCommit parent, String message, GitCommitAuthor author) throws IOException {
        CommitBuilder commitBuilder = new CommitBuilder();
        commitBuilder.setTreeId(treeId);
        if (parent != null) {
            commitBuilder.setParentId(parent);
        }
        PersonIdent authorIdentity = personIdent(author);
        commitBuilder.setAuthor(authorIdentity);
        commitBuilder.setCommitter(authorIdentity);
        commitBuilder.setEncoding(StandardCharsets.UTF_8);
        commitBuilder.setMessage(commitMessage(message));
        return inserter.insert(commitBuilder);
    }

    private static void updateBranch(Repository repository, String branchRefName, ObjectId oldBranchId, ObjectId commitId) throws IOException {
        RefUpdate update = repository.updateRef(branchRefName);
        update.setNewObjectId(commitId);
        update.setExpectedOldObjectId(oldBranchId == null ? ObjectId.zeroId() : oldBranchId);
        RefUpdate.Result result = update.update();
        switch (result) {
            case NEW, FAST_FORWARD, FORCED, NO_CHANGE -> {
            }
            default -> throw new IOException("Cannot update branch " + branchRefName + ": " + result);
        }
    }

    private static PersonIdent personIdent(GitCommitAuthor author) {
        author = Objects.requireNonNullElse(author, GitCommitAuthor.EMPTY);
        return new PersonIdent(author.name(), author.email());
    }

    private static String commitMessage(String message) {
        if (message == null || message.isBlank()) {
            return "update files";
        }
        return message;
    }

    private static String gitPath(String path) {
        Objects.requireNonNull(path, "path");
        Path rawPath = Path.of(path);
        if (rawPath.isAbsolute()) {
            throw new IllegalArgumentException("Git file path must be relative: " + path);
        }
        for (Path segment : rawPath) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("Git file path escapes repository: " + path);
            }
        }
        Path normalizedPath = rawPath.normalize();
        if (normalizedPath.toString().isBlank()) {
            throw new IllegalArgumentException("Git file path must not be empty");
        }
        return normalizedPath.toString().replace(File.separatorChar, '/');
    }

    private record TreeEntry(FileMode fileMode, ObjectId objectId) {
    }
}
