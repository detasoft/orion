package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.FileMode;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static pro.deta.orion.git.nativestorage.NativeRepositoryFileLoader.*;

final class NativeRepositoryFileSaver {
    private static final String NULL_ID = "0".repeat(40);

    private final NativeGitRepository repository;

    NativeRepositoryFileSaver(NativeGitRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    void saveFiles(
            String branch,
            Map<String, GitFile> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        publish(prepareFiles(branch, files, message, author));
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
            Map<String, GitFile> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return prepareFiles(branch, files, message, author, true);
    }

    NativeGitFileUpdate prepareFiles(
            String branch,
            Map<String, GitFile> files,
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
            Map<String, GitFile> files,
            String message,
            GitCommitAuthor author,
            boolean initializeDefaultHead) throws GitOperationException {
        Optional<ObjectId> parent = Optional.ofNullable(expectedRefRevision).map(ObjectId::new);
        return prepareFiles(branch, parent, files, message, author, initializeDefaultHead);
    }

    private NativeGitFileUpdate prepareFiles(
            String branch,
            Optional<ObjectId> parent,
            Map<String, GitFile> files,
            String message,
            GitCommitAuthor author,
            boolean initializeDefaultHead) throws GitOperationException {
        Objects.requireNonNull(files, "files");
        Map<ObjectId, LooseObject> preparedObjects = new LinkedHashMap<>();
        String branchRefName = branchRefName(branch);
        TreeMap<String, TreeEntry> treeEntries = new TreeMap<>();
        if (parent.isPresent()) {
            readTreeEntries(rootTreeId(parent.get(), readObject(parent.get())), "", treeEntries);
        }

        for (Map.Entry<String, GitFile> entry : files.entrySet()) {
            String path = gitPath(entry.getKey());
            GitFile file = Objects.requireNonNull(entry.getValue(), "file");
            ObjectId blobId = writeObject(preparedObjects, GitObjectType.BLOB, file.content());
            treeEntries.put(path, new TreeEntry(file.mode(), path.substring(path.lastIndexOf('/') + 1), blobId));
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

    private void readTreeEntries(
            ObjectId treeId,
            String prefix,
            TreeMap<String, TreeEntry> entries) throws GitOperationException {
        LooseObject tree = readObject(treeId);
        if (tree.type() != GitObjectType.TREE) {
            throw new GitOperationException("Tree target is not a tree: " + treeId);
        }
        byte[] data = tree.data();
        int offset = 0;
        while (offset < data.length) {
            ParsedTreeEntry parsed = parseTreeEntry(treeId, data, offset);
            String path = prefix + parsed.entry().name();
            if (parsed.entry().mode() == FileMode.TREE) {
                readTreeEntries(parsed.entry().objectId(), path + "/", entries);
            } else {
                entries.put(path, parsed.entry());
            }
            offset = parsed.nextOffset();
        }
    }

    private ObjectId writeTree(
            String prefix,
            TreeMap<String, TreeEntry> entries,
            Map<ObjectId, LooseObject> preparedObjects) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String previousDirectory = null;
        for (Map.Entry<String, TreeEntry> entry : entries.tailMap(prefix).entrySet()) {
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
                    writeTreeEntry(output, FileMode.TREE, directory, treeId);
                }
                continue;
            }
            writeTreeEntry(output, entry.getValue().mode(), relative, entry.getValue().objectId());
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

    private static void writeTreeEntry(
            ByteArrayOutputStream output,
            FileMode mode,
            String name,
            ObjectId objectId) {
        output.writeBytes((Integer.toOctalString(mode.code()) + " " + name + "\0").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.toHex()));
    }

    private static String commitMessage(String message) {
        if (message == null || message.isBlank()) {
            return "update files";
        }
        return message;
    }
}
