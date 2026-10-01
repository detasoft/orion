package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.api.Modification;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.NativeGitReceivePack;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;

import static pro.deta.orion.git.fileapi.GitFileApi.*;

/**
 * File changes against captured refs. Each write consumes exactly the declared number of bytes from a
 * borrowed input and stores compressed content immediately; only tree entries remain in memory.
 * Pack export freezes the changes without publishing them. Apply publishes the pack and updates refs
 * through the repository's publication policy. Discard releases resources and is harmless after completion.
 */
public final class GitFileAccess implements Modification {
    private final NativeGitRepository repository;
    private final GitIndexAccess index;
    private final PackId packId;
    private final PackHandle bytes;
    private final GitStorageAccess storage;
    private final RefId ref;
    private final Optional<ObjectId> parent;
    private final boolean initializeDefaultHead;
    private final String message;
    private final GitCommitAuthor author;
    private final Set<ObjectId> objects = new HashSet<>();
    private final Set<String> written = new HashSet<>();
    private final Set<String> deleted = new HashSet<>();
    private final TreeMap<String, TreeEntry> entries = new TreeMap<>((left, right) -> Arrays.compareUnsigned(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)));
    private PackMetadata pack;
    private List<RefUpdate> updates;
    private boolean finished;

    GitFileAccess(NativeGitRepository repository, GitIndexAccess index, String branch, Optional<ObjectId> parent,
                  String message, GitCommitAuthor author, boolean initializeDefaultHead)
            throws IOException, GitOperationException {
        this.repository = repository;
        this.index = index;
        this.packId = index.packId().orElseThrow(() -> new IllegalArgumentException("Pack access required"));
        this.ref = new RefId(branchRefName(branch));
        this.parent = parent;
        this.message = message;
        this.author = author;
        Map<RefId, ObjectId> refs = index.snapshotRefs().refs();
        this.initializeDefaultHead = initializeDefaultHead
                && !ref.value().equals(repository.defaultHead())
                && !refs.containsKey(new RefId(repository.defaultHead()));
        if (parent.isPresent()) {
            readTreeEntries(rootTreeId(parent.get(), readObject(parent.get())), "", entries);
        }
        storage = repository.storage().createAccess();
        try {
            bytes = storage.newPack(packId);
            bytes.write(0, ByteBuffer.allocate(8).putInt(0x4f52504b).putInt(1).flip());
        } catch (IOException | RuntimeException | Error failure) {
            try {
                storage.discard();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void write(String path, FileMode mode, long size, BufferedByteInputV2 input) throws IOException {
        requireEditable();
        String normalized = gitPath(path);
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(input, "input");
        if (mode == FileMode.TREE || mode == FileMode.GITLINK || size < 0) {
            throw new IllegalArgumentException("Invalid file mode or size");
        }
        if (deleted.contains(normalized)) {
            throw new IllegalArgumentException("Git file is both saved and deleted: " + normalized);
        }
        ObjectId id = writeObject(GitObjectType.BLOB, size, input);
        entries.put(normalized, new TreeEntry(mode, normalized.substring(normalized.lastIndexOf('/') + 1), id));
        written.add(normalized);
    }

    public void write(String path, byte[] content) throws IOException {
        write(path, FileMode.REGULAR_FILE, content);
    }

    public void write(String path, FileMode mode, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
            write(path, mode, content.length, input);
        }
    }

    public void delete(String path) throws IOException {
        requireEditable();
        String normalized = gitPath(path);
        if (written.contains(normalized)) {
            throw new IllegalArgumentException("Git file is both saved and deleted: " + normalized);
        }
        entries.remove(normalized);
        deleted.add(normalized);
    }

    public PackMetadata pack() throws IOException {
        requireOpen();
        if (pack != null) {
            return pack;
        }
        try {
            ObjectId tree = writeTree("", entries);
            ObjectId commit = writeCommit(tree, parent.orElse(null), message, author);
            List<RefUpdate> prepared = new ArrayList<>();
            prepared.add(new RefUpdate(ref, parent, Optional.of(commit)));
            if (initializeDefaultHead) {
                prepared.add(new RefUpdate(new RefId(repository.defaultHead()), Optional.empty(), Optional.of(commit)));
            }
            updates = List.copyOf(prepared);
            bytes.flush();
            List<IndexedObject> indexed = index.objects(packId);
            try (PackWriter writer = new PackWriter(
                    new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream()), indexed.size())) {
                writer.writeObjects(storage, indexed);
                pack = new PackMetadata(packId, writer.finish(), packId.toString(), indexed.size(), writer.size());
            }
            return pack;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                discard();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public List<RefUpdate> refUpdates() throws IOException {
        pack();
        return updates;
    }

    public void writePack(BufferedByteOutput output) throws IOException {
        PackMetadata metadata = pack();
        try (PackWriter writer = new PackWriter(output, metadata.objectCount())) {
            writer.writeObjects(storage, index.objects(packId));
            if (!writer.finish().equals(metadata.packChecksum())) {
                throw new IOException("File pack checksum changed during export");
            }
        }
    }

    @Override
    public void apply() throws IOException {
        requireOpen();
        Throwable primary = null;
        try {
            PackMetadata metadata = pack();
            storage.apply();
            index.publishIndex(metadata);
            List<RefUpdateResult> results = NativeGitReceivePack.complete(repository.name(), repository,
                    updates, true, GitNativeRepositoryAccessHook.ALLOW_ALL,
                    valid -> repository.publishReceivedPack(Optional.of(pack.packChecksum()), valid, true));
            for (RefUpdateResult result : results) {
                if (result.status() == RefUpdateResult.Status.EXPECTED_OLD_MISMATCH) {
                    Optional<ObjectId> actual = Optional.ofNullable(repository.refs().get(result.update().ref().value()))
                            .map(ObjectId::new);
                    throw new GitRefConflictException(result.update(), actual);
                }
            }
            GitOperationException.requireSuccess(results);
        } catch (GitOperationException failure) {
            IOException wrapped = new IOException("Cannot apply file changes", failure);
            primary = wrapped;
            throw wrapped;
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                discard();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (primary == null) {
                    throw cleanup;
                }
                primary.addSuppressed(cleanup);
            }
        }
    }

    @Override
    public void discard() throws IOException {
        if (!finished) {
            finished = true;
            Throwable primary = null;
            try {
                storage.discard();
            } catch (IOException | RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                try {
                    index.discard();
                } catch (IOException | RuntimeException | Error cleanup) {
                    if (primary == null) throw cleanup;
                    primary.addSuppressed(cleanup);
                }
            }
        }
    }

    private ObjectId writeObject(GitObjectType type, byte[] content) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
            return writeObject(type, content.length, input);
        }
    }

    private ObjectId writeObject(GitObjectType type, long size, BufferedByteInputV2 input) throws IOException {
        MessageDigest hash = repository.hashAlgorithm().newDigest();
        hash.update((type.name().toLowerCase(Locale.ROOT) + " " + size + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        long start = bytes.size();
        Deflater deflater = new Deflater();
        byte[] compressed = new byte[8192];
        try {
            long remaining = size;
            while (remaining > 0) {
                ByteBuffer source = input.buffer();
                if (source == null) {
                    throw new EOFException("File content is shorter than declared size");
                }
                int count = (int) Math.min(remaining, source.remaining());
                ByteBuffer part = source.slice(source.position(), count);
                hash.update(part.duplicate());
                deflater.setInput(part);
                while (!deflater.needsInput()) {
                    int length = deflater.deflate(compressed);
                    bytes.write(bytes.size(), ByteBuffer.wrap(compressed, 0, length));
                }
                source.position(source.position() + count);
                remaining -= count;
            }
            deflater.finish();
            while (!deflater.finished()) {
                int length = deflater.deflate(compressed);
                bytes.write(bytes.size(), ByteBuffer.wrap(compressed, 0, length));
            }
            ObjectId id = new ObjectId(hash.digest());
            if (objects.contains(id) || !index.locations(id).isEmpty()) {
                bytes.truncate(start);
            } else {
                index.addObject(new IndexedObject(packId, id, type, size, start, bytes.size() - start, Optional.empty()));
                objects.add(id);
            }
            return id;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                discard();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            deflater.end();
        }
    }

    private void requireOpen() throws IOException {
        if (finished) {
            throw new IOException("File access is finished");
        }
    }

    private void requireEditable() throws IOException {
        requireOpen();
        if (pack != null) {
            throw new IOException("File pack is already prepared");
        }
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
            TreeMap<String, TreeEntry> entries) throws IOException {
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
                            entries);
                    writeTreeEntry(output, FileMode.TREE, directory, treeId);
                }
                continue;
            }
            writeTreeEntry(output, entry.getValue().mode(), relative, entry.getValue().objectId());
        }
        ObjectId treeId = writeObject(GitObjectType.TREE, output.toByteArray());
        return treeId;
    }

    private ObjectId writeCommit(
            ObjectId treeId,
            ObjectId parent,
            String message,
            GitCommitAuthor author) throws IOException {
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
        return writeObject(
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
