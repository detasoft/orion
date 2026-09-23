package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class NativeRepositoryFileLoader {
    private final NativeGitRepository repository;

    NativeRepositoryFileLoader(NativeGitRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    GitRepositoryFileSnapshot loadFiles(String branch, List<String> paths)
            throws GitOperationException {
        Objects.requireNonNull(paths, "paths");
        ObjectId commitId = resolveBranch(branch);
        LooseObject commit = readObject(commitId);
        ObjectId rootTreeId = rootTreeId(commitId, commit);
        Map<String, GitFile> files = new LinkedHashMap<>();
        for (String path : paths) {
            String gitPath = gitPath(path);
            TreeEntry entry = resolvePath(rootTreeId, gitPath);
            if (entry.mode() == FileMode.TREE || entry.mode() == FileMode.GITLINK) {
                throw new GitOperationException("Path is not a file: " + gitPath);
            }
            LooseObject object = readObject(entry.objectId());
            if (object.type() != GitObjectType.BLOB) {
                throw new GitOperationException("File target is not a blob: " + gitPath);
            }
            files.put(gitPath, new GitFile(entry.mode(), object.data()));
        }
        return new GitRepositoryFileSnapshot(files, Optional.of(commitId.toHex()));
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
