package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.object.LooseObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

final class GitFileApiSupport {
    private GitFileApiSupport() {}

    static GitFileAccess open(GitFileApi resource, VcRevision.ReadTarget target) {
        NativeGitRepository repository = resource.repository;
        try {
            VcRevision revision = switch (target) {
                case VcRevision.Latest latest -> repository.index().resolve(latest);
                case VcRevision.Stored stored -> stored;
                case VcRevision.Absent absent -> absent;
            };
            GitIndexAccess index = repository.index().createAccess(Optional.of(PackId.create()));
            try {
                return new GitFileAccess(resource, index, revision);
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
            throw new IllegalStateException("Cannot open Git revision", failure);
        }
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
