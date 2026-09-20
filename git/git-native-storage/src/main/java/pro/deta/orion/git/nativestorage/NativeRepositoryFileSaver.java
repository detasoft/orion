package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

final class NativeRepositoryFileSaver {
    private static final String NULL_ID = "0".repeat(40);

    private final NativeGitRepository repository;

    NativeRepositoryFileSaver(NativeGitRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    void saveFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        publish(prepareFiles(branch, files, message, author));
    }

    void saveFilesIfVersion(
            String branch,
            String expectedVersion,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        publish(prepareFiles(branch, expectedVersion, files, message, author, true));
    }

    private void publish(NativeGitFileUpdate update) throws GitOperationException {
        List<RefUpdateResult> results = repository.publishPack(
                update.pack(),
                update.refUpdates(),
                true, GitNativeRepositoryAccessHook.ALLOW_ALL);
        GitOperationException.requireSuccess(results);
    }

    NativeGitFileUpdate prepareFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return prepareFiles(branch, files, message, author, true);
    }

    NativeGitFileUpdate prepareFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author,
            boolean initializeDefaultHead) throws GitOperationException {
        return prepareFiles(
                branch,
                resolveBranch(branch),
                files,
                message,
                author,
                initializeDefaultHead);
    }

    NativeGitFileUpdate prepareFiles(
            String branch,
            String expectedRefRevision,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author,
            boolean initializeDefaultHead) throws GitOperationException {
        Optional<ObjectId> parent = Optional.ofNullable(expectedRefRevision).map(ObjectId::new);
        return prepareFiles(branch, parent, files, message, author, initializeDefaultHead);
    }

    private NativeGitFileUpdate prepareFiles(
            String branch,
            Optional<ObjectId> parent,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author,
            boolean initializeDefaultHead) throws GitOperationException {
        Objects.requireNonNull(files, "files");
        Map<ObjectId, LooseObject> preparedObjects = new LinkedHashMap<>();
        String branchRefName = branchRefName(branch);
        TreeMap<String, ObjectId> treeEntries = new TreeMap<>();
        if (parent.isPresent()) {
            readTreeEntries(rootTreeId(parent.get()), "", treeEntries);
        }

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = gitPath(entry.getKey());
            ObjectId blobId = writeObject(preparedObjects, GitObjectType.BLOB, entry.getValue());
            treeEntries.put(path, blobId);
        }

        ObjectId treeId = writeTree("", treeEntries, preparedObjects);
        ObjectId commitId = writeCommit(
                treeId,
                parent.orElse(null),
                message,
                author,
                preparedObjects);
        String expectedOldId = parent.map(ObjectId::toHex).orElse(NULL_ID);
        List<RefUpdate> updates = new java.util.ArrayList<>();
        updates.add(RefUpdate.fromWire(branchRefName, expectedOldId, commitId.toHex()));
        if (initializeDefaultHead
                && !repository.refs().containsKey(repository.defaultHead())
                && !repository.defaultHead().equals(branchRefName)) {
            updates.add(RefUpdate.fromWire(
                    repository.defaultHead(),
                    NULL_ID,
                    commitId.toHex()));
        }
        return new NativeGitFileUpdate(buildPack(preparedObjects), updates);
    }

    private static byte[] buildPack(Map<ObjectId, LooseObject> objects) throws GitOperationException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), objects.size())) {
            for (LooseObject object : objects.values()) {
                byte[] data = object.data();
                try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(data))) {
                    writer.writeObject(object.type(), data.length, input);
                }
            }
            writer.finish();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new GitOperationException("Cannot build file update pack", failure);
        }
    }

    private static ObjectId writeObject(Map<ObjectId, LooseObject> objects, GitObjectType type, byte[] content) {
        MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
        hash.update((type.name().toLowerCase(Locale.ROOT) + " " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        ObjectId id = new ObjectId(HexFormat.of().formatHex(hash.digest(content)));
        objects.put(id, new LooseObject(id, type, content));
        return id;
    }

    private Optional<ObjectId> resolveBranch(String branch) {
        Map<String, String> refs = repository.refs();
        String refName = branchRefName(branch);
        String objectId = refs.get(refName);
        if (objectId == null && !branch.startsWith("refs/")) {
            objectId = refs.get(branch);
        }
        return Optional.ofNullable(objectId).map(ObjectId::new);
    }

    private ObjectId rootTreeId(ObjectId commitId)
            throws GitOperationException {
        LooseObject commit = readObject(commitId);
        if (commit.type() != GitObjectType.COMMIT) {
            throw new GitOperationException("Branch target is not a commit: " + commitId);
        }
        int offset = 0;
        byte[] data = commit.data();
        while (offset < data.length) {
            int lineEnd = lineEnd(data, offset);
            if (lineEnd == offset) {
                break;
            }
            String line = new String(data, offset, lineEnd - offset, StandardCharsets.US_ASCII);
            if (line.startsWith("tree ")) {
                return new ObjectId(line.substring("tree ".length()));
            }
            offset = lineEnd + 1;
        }
        throw new GitOperationException("Commit is missing root tree: " + commitId);
    }

    private void readTreeEntries(
            ObjectId treeId,
            String prefix,
            TreeMap<String, ObjectId> entries) throws GitOperationException {
        LooseObject tree = readObject(treeId);
        if (tree.type() != GitObjectType.TREE) {
            throw new GitOperationException("Tree target is not a tree: " + treeId);
        }
        byte[] data = tree.data();
        int offset = 0;
        while (offset < data.length) {
            ParsedTreeEntry parsed = parseTreeEntry(treeId, data, offset);
            String path = prefix + parsed.entry().name();
            if ("40000".equals(parsed.entry().mode())) {
                readTreeEntries(parsed.entry().objectId(), path + "/", entries);
            } else {
                entries.put(path, parsed.entry().objectId());
            }
            offset = parsed.nextOffset();
        }
    }

    private ObjectId writeTree(
            String prefix,
            TreeMap<String, ObjectId> entries,
            Map<ObjectId, LooseObject> preparedObjects) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String previousDirectory = null;
        for (Map.Entry<String, ObjectId> entry : entries.tailMap(prefix).entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                break;
            }
            String relative = entry.getKey().substring(prefix.length());
            if (relative.isEmpty()) {
                continue;
            }
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                String directory = relative.substring(0, slash);
                if (!directory.equals(previousDirectory)) {
                    previousDirectory = directory;
                    ObjectId treeId = writeTree(
                            prefix + directory + "/",
                            entries,
                            preparedObjects);
                    writeTreeEntry(output, "40000", directory, treeId);
                }
                continue;
            }
            writeTreeEntry(output, "100644", relative, entry.getValue());
        }
        ObjectId treeId = writeObject(preparedObjects, GitObjectType.TREE, output.toByteArray());
        return treeId;
    }

    private ObjectId writeCommit(
            ObjectId treeId,
            ObjectId parent,
            String message,
            GitCommitAuthor author,
            Map<ObjectId, LooseObject> preparedObjects) {
        GitCommitAuthor commitAuthor = Objects.requireNonNullElse(author, GitCommitAuthor.EMPTY);
        String identity = commitAuthor.name() + " <" + commitAuthor.email() + "> 0 +0000";
        StringBuilder data = new StringBuilder()
                .append("tree ")
                .append(treeId)
                .append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author ").append(identity).append('\n')
                .append("committer ").append(identity).append('\n')
                .append('\n')
                .append(commitMessage(message))
                .append('\n');
        return writeObject(preparedObjects,
                GitObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private LooseObject readObject(ObjectId objectId) throws GitOperationException {
        return repository.readObject(objectId)
                .orElseThrow(() -> new GitOperationException("Object not found: " + objectId));
    }

    private static ParsedTreeEntry parseTreeEntry(
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
        String mode = new String(data, modeStart, offset - modeStart, StandardCharsets.US_ASCII);
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

    private static void writeTreeEntry(
            ByteArrayOutputStream output,
            String mode,
            String name,
            ObjectId objectId) {
        output.writeBytes((mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.toHex()));
    }

    private static int lineEnd(byte[] data, int offset) {
        int index = offset;
        while (index < data.length && data[index] != '\n') {
            index++;
        }
        return index;
    }

    private static String branchRefName(String branch) {
        Objects.requireNonNull(branch, "branch");
        if (branch.startsWith("refs/")) {
            return branch;
        }
        return "refs/heads/" + branch;
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

    private record TreeEntry(
            String mode,
            String name,
            ObjectId objectId) {
    }

    private record ParsedTreeEntry(
            TreeEntry entry,
            int nextOffset) {
    }
}
