package pro.deta.orion.git.storage;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public class LocalGitFileStorage {
    private static final String DEFAULT_COMMIT_MESSAGE = "update files";
    private static final String DEFAULT_AUTHOR_NAME = "orion";
    private static final String DEFAULT_AUTHOR_EMAIL = "orion@localhost";

    private final Path repositoryPath;

    public LocalGitFileStorage(Path repositoryPath) {
        this.repositoryPath = Objects.requireNonNull(repositoryPath, "repositoryPath").toAbsolutePath().normalize();
    }

    public Result<GitFileSnapshot> load(String branch, String path) {
        String gitPath = gitPath(path);
        try (Repository repository = openRepository(); RevWalk revWalk = new RevWalk(repository)) {
            ObjectId branchId = resolveBranch(repository, branch);
            if (branchId == null) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }

            RevCommit commit = revWalk.parseCommit(branchId);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, gitPath, commit.getTree())) {
                if (treeWalk == null) {
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
                }
                byte[] content = repository.open(treeWalk.getObjectId(0)).getBytes();
                return new Result.Success<>(new GitFileSnapshot(
                        Map.of(gitPath, content),
                        Optional.of(commit.name())));
            }
        } catch (IOException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    public void save(String branch, Map<String, byte[]> files, String message, UserEmail author) {
        String branchRefName = branchRefName(branch);
        try (Repository repository = openRepository();
             RevWalk revWalk = new RevWalk(repository);
             ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId oldBranchId = resolveBranch(repository, branch);
            RevCommit parent = oldBranchId == null ? null : revWalk.parseCommit(oldBranchId);

            TreeMap<String, TreeEntry> treeEntries = new TreeMap<>();
            if (parent != null) {
                readTreeEntries(repository, parent, treeEntries);
            }

            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String path = gitPath(entry.getKey());
                ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, entry.getValue());
                treeEntries.put(path, new TreeEntry(FileMode.REGULAR_FILE, blobId));
            }

            ObjectId treeId = writeTree(treeEntries, inserter);
            ObjectId commitId = writeCommit(inserter, treeId, parent, message, author);
            inserter.flush();
            updateBranch(repository, branchRefName, oldBranchId, commitId);
        } catch (IOException e) {
            throw new RuntimeException("Cannot save files to local git repository " + repositoryPath, e);
        }
    }

    private Repository openRepository() throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Path worktreeGitDir = repositoryPath.resolve(".git");
        if (Files.isDirectory(worktreeGitDir)) {
            return builder.setGitDir(worktreeGitDir.toFile()).build();
        }
        return builder.setBare().setGitDir(repositoryPath.toFile()).build();
    }

    private static ObjectId resolveBranch(Repository repository, String branch) throws IOException {
        ObjectId objectId = repository.resolve(branchRefName(branch));
        if (objectId == null && !branch.startsWith(Constants.R_REFS)) {
            objectId = repository.resolve(branch);
        }
        return objectId;
    }

    private static String branchRefName(String branch) {
        Objects.requireNonNull(branch, "branch");
        if (branch.startsWith(Constants.R_REFS)) {
            return branch;
        }
        return Constants.R_HEADS + branch;
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

    private static ObjectId writeCommit(ObjectInserter inserter, ObjectId treeId, RevCommit parent, String message, UserEmail author) throws IOException {
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

    private static PersonIdent personIdent(UserEmail author) {
        author = Objects.requireNonNullElse(author, UserEmail.EMPTY);
        String username = author.getUsername();
        if (username == null || username.isBlank()) {
            username = DEFAULT_AUTHOR_NAME;
        }
        String email = author.getEmail();
        if (email == null || email.isBlank()) {
            email = DEFAULT_AUTHOR_EMAIL;
        }
        return new PersonIdent(username, email);
    }

    private static String commitMessage(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_COMMIT_MESSAGE;
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
