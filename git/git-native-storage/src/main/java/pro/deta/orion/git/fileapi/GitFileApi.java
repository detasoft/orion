package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.internal.CheckedFunction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * File operations borrowing a Git repository. Updates preserve omitted files and their modes;
 * preparing an update does not publish objects or move refs. An explicit null revision expects a new branch.
 * Local updates initialize an absent default branch; proxy updates leave unrelated refs unchanged.
 * Streaming read callbacks borrow the inflated file input. WithAccess requires explicit apply and
 * always discards on exit. Byte-array reads explicitly materialize a single file.
 */
public final class GitFileApi {
    private final NativeGitRepository repository;
    private final boolean initializeDefaultHead;

    public GitFileApi(NativeGitRepository repository) {
        this(repository, true);
    }

    public GitFileApi(NativeGitRepository repository, boolean initializeDefaultHead) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.initializeDefaultHead = initializeDefaultHead;
    }

    public <T> T readFile(ObjectId commitId, String path, GitObjectRead<T> reader)
            throws IOException, GitOperationException {
        Objects.requireNonNull(reader, "reader");
        String normalized = gitPath(path);
        ObjectId tree = rootTreeId(commitId, readObject(commitId));
        TreeEntry entry = resolvePath(tree, normalized);
        if (entry.mode() == FileMode.TREE || entry.mode() == FileMode.GITLINK) {
            throw new GitOperationException("Path is not a file: " + normalized);
        }
        return repository.storage().withAccess(bytes -> {
            Optional<T> result = repository.index().withAccess(index -> GitObjectRead.read(
                    bytes, index, entry.objectId(),
                    new ResolvedGitObjectRead<>(bytes, index, (type, size, base, input) -> {
                        if (type != GitObjectType.BLOB) {
                            throw new IOException("File target is not a blob: " + normalized);
                        }
                        return reader.read(type, size, base, input);
                    })));
            return result.orElseThrow(() -> new GitOperationException("Object not found: " + entry.objectId()));
        });
    }

    public <T> T readFile(String branch, String path, GitObjectRead<T> reader)
            throws IOException, GitOperationException {
        return readFile(resolveBranch(branch), path, reader);
    }

    public byte[] readBytes(String branch, String path) throws IOException, GitOperationException {
        return readBytes(resolveBranch(branch), path);
    }

    public byte[] readBytes(ObjectId commitId, String path) throws IOException, GitOperationException {
        return readFile(commitId, path, (type, size, base, input) -> input.newInputStream().readAllBytes());
    }

    public <T> T withAccess(String branch, String message, GitCommitAuthor author,
                            CheckedFunction<GitFileAccess, T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        RefId ref = new RefId(branchRefName(branch));
        return repository.index().withAccess(writableRefs(ref), Optional.of(PackId.create()),
                index -> run(index, branch,
                        Optional.ofNullable(index.snapshotRefs().refs().get(ref)), message, author, operation));
    }

    private Set<RefId> writableRefs(RefId ref) {
        Set<RefId> refs = new HashSet<>();
        refs.add(ref);
        if (initializeDefaultHead) {
            refs.add(new RefId(repository.defaultHead()));
        }
        return refs;
    }

    public <T> T withAccess(String branch, String expectedRevision, String message, GitCommitAuthor author,
                            CheckedFunction<GitFileAccess, T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        RefId ref = new RefId(branchRefName(branch));
        Optional<ObjectId> expected = Optional.ofNullable(expectedRevision).map(ObjectId::new);
        return repository.index().withAccess(writableRefs(ref), Optional.of(PackId.create()),
                index -> run(index, branch, expected, message, author, operation));
    }

    private <T> T run(GitIndexAccess index, String branch, Optional<ObjectId> parent,
                      String message, GitCommitAuthor author,
                      CheckedFunction<GitFileAccess, T> operation) throws Exception {
        GitFileAccess access = new GitFileAccess(
                repository, index, branch, parent, message, author, initializeDefaultHead);
        Throwable primary = null;
        try {
            return operation.apply(access);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                access.discard();
            } catch (Exception | Error cleanup) {
                if (primary == null) {
                    throw cleanup;
                }
                primary.addSuppressed(cleanup);
            }
        }
    }

    private ObjectId resolveBranch(String branch)
            throws GitRepositoryFileNotFoundException {
        Map<String, String> refs = repository.refs();
        String refName = branchRefName(branch);
        String objectId = refs.get(refName);
        if (objectId == null && !branch.startsWith("refs/")) {
            objectId = refs.get(branch);
        }
        if (objectId == null) {
            throw new GitRepositoryFileNotFoundException("Branch not found: " + branch);
        }
        return new ObjectId(objectId);
    }

    private TreeEntry resolvePath(ObjectId rootTreeId, String path)
            throws GitRepositoryFileNotFoundException, GitOperationException {
        String[] segments = path.split("/");
        ObjectId treeId = rootTreeId;
        TreeEntry entry = null;
        for (int index = 0; index < segments.length; index++) {
            entry = treeEntry(treeId, segments[index]);
            if (index < segments.length - 1) {
                if (entry.mode() != FileMode.TREE) {
                    throw new GitOperationException("Path segment is not a directory: " + segments[index]);
                }
                treeId = entry.objectId();
            }
        }
        if (entry == null) {
            throw new GitRepositoryFileNotFoundException("File not found: " + path);
        }
        return entry;
    }

    private TreeEntry treeEntry(ObjectId treeId, String name)
            throws GitRepositoryFileNotFoundException, GitOperationException {
        LooseObject tree = readObject(treeId);
        if (tree.type() != GitObjectType.TREE) {
            throw new GitOperationException("Path segment target is not a tree: " + treeId);
        }
        byte[] data = tree.data();
        int offset = 0;
        while (offset < data.length) {
            ParsedTreeEntry entry = parseTreeEntry(treeId, data, offset);
            if (entry.entry().name().equals(name)) {
                return entry.entry();
            }
            offset = entry.nextOffset();
        }
        throw new GitRepositoryFileNotFoundException("File not found: " + name);
    }

    private LooseObject readObject(ObjectId objectId) throws GitOperationException {
        return repository.readObject(objectId)
                .orElseThrow(() -> new GitOperationException("Object not found: " + objectId));
    }

    static ObjectId rootTreeId(ObjectId commitId, LooseObject commit)
            throws GitOperationException {
        if (commit.type() != GitObjectType.COMMIT) {
            throw new GitOperationException("Branch target is not a commit: " + commitId);
        }
        byte[] data = commit.data();
        int offset = 0;
        while (offset < data.length) {
            int lineEnd = lineEnd(data, offset);
            if (lineEnd == offset) {
                break;
            }
            String line = new String(
                    data,
                    offset,
                    lineEnd - offset,
                    StandardCharsets.US_ASCII);
            if (line.startsWith("tree ")) {
                return new ObjectId(line.substring("tree ".length()));
            }
            offset = lineEnd + 1;
        }
        throw new GitOperationException("Commit is missing root tree: " + commitId);
    }

    static ParsedTreeEntry parseTreeEntry(
            ObjectId treeId,
            byte[] data,
            int offset) throws GitOperationException {
        int modeStart = offset;
        while (offset < data.length && data[offset] != ' ') {
            offset++;
        }
        if (offset == data.length || offset == modeStart) {
            throw new GitOperationException("Malformed tree entry mode in " + treeId);
        }
        FileMode mode;
        try {
            mode = FileMode.fromCode(Integer.parseInt(
                    new String(data, modeStart, offset - modeStart, StandardCharsets.US_ASCII), 8));
        } catch (IllegalArgumentException failure) {
            throw new GitOperationException("Malformed tree entry mode in " + treeId, failure);
        }
        offset++;

        int nameStart = offset;
        while (offset < data.length && data[offset] != 0) {
            offset++;
        }
        if (offset == data.length || offset == nameStart) {
            throw new GitOperationException("Malformed tree entry name in " + treeId);
        }
        String name = new String(data, nameStart, offset - nameStart, StandardCharsets.UTF_8);
        offset++;

        if (offset + 20 > data.length) {
            throw new GitOperationException("Malformed tree entry object id in " + treeId);
        }
        byte[] rawObjectId = new byte[20];
        System.arraycopy(data, offset, rawObjectId, 0, rawObjectId.length);
        ObjectId objectId = new ObjectId(HexFormat.of().formatHex(rawObjectId));
        return new ParsedTreeEntry(new TreeEntry(mode, name, objectId), offset + 20);
    }

    private static int lineEnd(byte[] data, int offset) {
        int index = offset;
        while (index < data.length && data[index] != '\n') {
            index++;
        }
        return index;
    }

    static String branchRefName(String branch) {
        Objects.requireNonNull(branch, "branch");
        if (branch.startsWith("refs/")) {
            return branch;
        }
        return "refs/heads/" + branch;
    }

    static String gitPath(String path) {
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

    record TreeEntry(
            FileMode mode,
            String name,
            ObjectId objectId) {

        TreeEntry {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(objectId, "objectId");
        }
    }

    record ParsedTreeEntry(
            TreeEntry entry,
            int nextOffset) {

        ParsedTreeEntry {
            Objects.requireNonNull(entry, "entry");
        }
    }
}
