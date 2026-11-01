package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class GitFileApiSupport {
    private GitFileApiSupport() {}

    static VcRevision initialRevision(NativeGitRepository repository, String branch) {
        String revision = repository.refs().get(branchRefName(branch));
        return revision == null ? new VcRevision.Absent() : new VcRevision.Stored(revision);
    }

    static GitFileAccess createAccess(NativeGitRepository repository, String branch, boolean initializeHead) {
        try {
            RefId ref = new RefId(branch);
            Head head = repository.index().getHEAD();
            GitIndexAccess index = repository.index().createAccess(
                    writableRefs(ref, head, initializeHead), Optional.of(PackId.create()));
            try {
                return new GitFileAccess(repository, index, branch, initializeHead, head);
            } catch (IOException | GitOperationException | RuntimeException | Error failure) {
                try {
                    index.discard();
                } catch (IOException | RuntimeException | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (GitOperationException failure) {
            throw new IllegalStateException("Cannot open Git modification", failure);
        }
    }

    private static Set<RefId> writableRefs(RefId ref, Head head, boolean initializeHead) {
        Set<RefId> refs = new HashSet<>();
        refs.add(ref);
        if (initializeHead && head instanceof Head.Symbolic symbolic) {
            refs.add(symbolic.target());
        }
        return refs;
    }

    static <T> T readFile(NativeGitRepository repository, ObjectId commitId, String path, GitObjectRead<T> reader)
            throws IOException, GitOperationException {
        Objects.requireNonNull(reader, "reader");
        TreeEntry entry = fileEntry(repository, commitId, path);
        return repository.storage().withAccess(bytes -> {
            Optional<T> result = repository.index().withAccess(index -> GitObjectRead.read(
                    bytes, index, entry.objectId(),
                    new ResolvedGitObjectRead<>(bytes, index, (type, size, base, input) -> {
                        if (type != GitObjectType.BLOB) {
                            throw new IOException("File target is not a blob: " + path);
                        }
                        return reader.read(type, size, base, input);
                    })));
            return result.orElseThrow(() -> new GitOperationException("Object not found: " + entry.objectId()));
        });
    }

    static TreeEntry fileEntry(NativeGitRepository repository, ObjectId commitId, String path)
            throws GitOperationException {
        String normalized = gitPath(path);
        ObjectId tree = rootTreeId(commitId, readObject(repository, commitId));
        TreeEntry entry = resolvePath(repository, tree, normalized);
        if (entry.mode() == FileMode.TREE || entry.mode() == FileMode.GITLINK) {
            throw new GitOperationException("Path is not a file: " + normalized);
        }
        return entry;
    }

    // @TODO перенеси в NativeGitRepository чтобы не было здесь логики создания "refs/*"
    static ObjectId resolveBranch(NativeGitRepository repository, String branch)
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

    private static TreeEntry resolvePath(NativeGitRepository repository, ObjectId rootTreeId, String path)
            throws GitRepositoryFileNotFoundException, GitOperationException {
        String[] segments = path.split("/");
        ObjectId treeId = rootTreeId;
        TreeEntry entry = null;
        for (int index = 0; index < segments.length; index++) {
            entry = treeEntry(repository, treeId, segments[index]);
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

    private static TreeEntry treeEntry(NativeGitRepository repository, ObjectId treeId, String name)
            throws GitRepositoryFileNotFoundException, GitOperationException {
        LooseObject tree = readObject(repository, treeId);
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

    private static LooseObject readObject(NativeGitRepository repository, ObjectId objectId)
            throws GitOperationException {
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
