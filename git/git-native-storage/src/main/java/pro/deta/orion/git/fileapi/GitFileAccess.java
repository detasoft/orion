package pro.deta.orion.git.fileapi;
import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.Deflater;
import static pro.deta.orion.git.fileapi.GitFileApiSupport.*;

/**
 * Reads and prepares file changes against one stored commit. Committing publishes objects;
 * updating a ref is a separate repository operation.
 */
public final class GitFileAccess extends VcResource<GitFileAccess>.VcOperation {
    private final NativeGitRepository repository;
    private final GitIndexAccess index;
    private final PackId packId;
    private PackHandle bytes;
    private final GitStorageAccess storage;
    private final Map<String, TreeEntry> rootEntries;
    private final Set<String> deleted = new HashSet<>();
    private final Map<String, TreeEntry> entries = new HashMap<>();

    GitFileAccess(GitFileApi resource, GitIndexAccess index, VcRevision.Stored revision)
            throws IOException, GitOperationException {
        resource.super(revision);
        this.repository = resource.repository;
        this.index = index;
        this.packId = index.packId().orElseThrow(() -> new IllegalArgumentException("Pack access required"));
        storage = repository.storage().createAccess();
        try {
            ObjectId parent = new ObjectId(revision.value());
            rootEntries = readTree(rootTreeId(parent, readObject(parent)));
        } catch (IOException | GitOperationException | RuntimeException | Error failure) {
            try {
                storage.discard();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private TreeEntry fileEntry(String path) throws IOException, GitOperationException {
        String normalized = gitPath(path);
        for (int slash = normalized.indexOf('/'); slash >= 0; slash = normalized.indexOf('/', slash + 1)) {
            String ancestor = normalized.substring(0, slash);
            if (deleted.contains(ancestor) || entries.containsKey(ancestor)) {
                throw new GitRepositoryFileNotFoundException("File not found: " + normalized);
            }
        }
        if (deleted.contains(normalized)) {
            throw new GitRepositoryFileNotFoundException("File not found: " + normalized);
        }
        TreeEntry entry = entries.get(normalized);
        if (entry == null) {
            for (String changed : entries.keySet()) {
                if (changed.startsWith(normalized + "/")) {
                    throw new GitOperationException("Path is not a file: " + normalized);
                }
            }
            Map<String, TreeEntry> directory = rootEntries;
            String[] segments = normalized.split("/");
            for (int position = 0; position < segments.length; position++) {
                entry = directory.get(segments[position]);
                if (entry == null) {
                    throw new GitRepositoryFileNotFoundException("File not found: " + normalized);
                }
                if (position < segments.length - 1) {
                    if (entry.mode() != FileMode.TREE) {
                        throw new GitRepositoryFileNotFoundException("File not found: " + normalized);
                    }
                    directory = readTree(entry.objectId());
                }
            }
        }
        if (entry.mode() == FileMode.TREE || entry.mode() == FileMode.GITLINK) {
            throw new GitOperationException("Path is not a file: " + normalized);
        }
        return entry;
    }

    public ObjectId objectId(String path) throws IOException, GitOperationException {
        return fileEntry(path).objectId();
    }

    public byte[] readBytes(String path) throws IOException, GitOperationException {
        return readFile(path, (type, size, base, input) -> input.newInputStream().readAllBytes());
    }

    public <T> T readFile(String path, GitObjectRead<T> reader) throws IOException, GitOperationException {
        Objects.requireNonNull(reader, "reader");
        TreeEntry entry = fileEntry(path);
        return readObject(entry.objectId(), (type, size, base, input) -> {
            if (type != GitObjectType.BLOB) throw new IOException("File target is not a blob: " + path);
            return reader.read(type, size, base, input);
        });
    }

    public void write(String path, FileMode mode, long size, BufferedByteInputV2 input) throws IOException {
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
        String normalized = gitPath(path);
        if (entries.containsKey(normalized)) {
            throw new IllegalArgumentException("Git file is both saved and deleted: " + normalized);
        }
        deleted.add(normalized);
    }

    @Override
    protected VcRevision.Stored commitChanges(VcChangeDescription description) {
        Objects.requireNonNull(description, "description");
        if (entries.isEmpty() && deleted.isEmpty()) return revision();
        try {
            Set<String> changedPaths = new HashSet<>(entries.keySet());
            changedPaths.addAll(deleted);
            ObjectId tree = writeTree("", null, changedPaths).orElseThrow();
            ObjectId commit = writeCommit(tree, new ObjectId(revision().value()), description);
            bytes.flush();
            List<IndexedObject> indexed = index.objects(packId);
            PackMetadata pack;
            try (PackWriter writer = new PackWriter(
                    new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream()), indexed.size())) {
                writer.writeObjects(storage, indexed);
                pack = new PackMetadata(packId, writer.finish(), packId.toString(), indexed.size(), writer.size());
            }
            storage.apply();
            index.publishIndex(pack);
            return new VcRevision.Stored(commit.toHex());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void close() {
        try {
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
                    if (primary != cleanup) primary.addSuppressed(cleanup);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
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
        if (bytes == null) {
            bytes = storage.newPack(packId);
            bytes.write(0, ByteBuffer.allocate(8).putInt(0x4f52504b).putInt(1).flip());
        }
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
            if (index.findObject(packId, id).isPresent() || !index.locations(id).isEmpty()) {
                bytes.truncate(start);
            } else {
                index.addObject(new IndexedObject(packId, id, type, size, start, bytes.size() - start, Optional.empty()));
            }
            return id;
        } finally {
            deflater.end();
        }
    }

    private Optional<ObjectId> writeTree(String prefix, ObjectId previousTree, Set<String> changedPaths)
            throws IOException {
        Map<String, TreeEntry> directory;
        if (prefix.isEmpty()) {
            directory = new HashMap<>(rootEntries);
        } else {
            try {
                directory = readTree(previousTree);
            } catch (GitOperationException failure) {
                throw new IOException("Cannot read tree: " + previousTree, failure);
            }
        }
        Set<String> changedNames = new HashSet<>();
        for (String path : changedPaths) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String relative = path.substring(prefix.length());
            int slash = relative.indexOf('/');
            changedNames.add(slash < 0 ? relative : relative.substring(0, slash));
        }
        for (String name : changedNames) {
            String path = prefix + name;
            TreeEntry replacement = entries.get(path);
            boolean remove = deleted.contains(path);
            boolean nestedChange = false;
            for (String changed : changedPaths) {
                if (changed.startsWith(path + "/")) {
                    nestedChange = true;
                    break;
                }
            }
            TreeEntry previous = directory.get(name);
            if (nestedChange) {
                if (replacement != null && (previous == null || previous.mode() != FileMode.TREE)) {
                    throw new IOException("Git file conflicts with a changed directory: " + path);
                }
                if (previous != null && previous.mode() == FileMode.TREE && remove) {
                    throw new IOException("Git path is a directory: " + path);
                }
                if (previous != null && previous.mode() != FileMode.TREE && !remove) {
                    throw new IOException("Git path is not a directory: " + path);
                }
                ObjectId previousChild = previous != null && previous.mode() == FileMode.TREE
                        ? previous.objectId() : null;
                Optional<ObjectId> updated = writeTree(path + "/", previousChild, changedPaths);
                if (replacement != null) {
                    if (updated.isPresent()) {
                        throw new IOException("Git file conflicts with a changed directory: " + path);
                    }
                    directory.put(name, replacement);
                } else if (updated.isPresent()) {
                    directory.put(name, new TreeEntry(FileMode.TREE, name, updated.orElseThrow()));
                } else {
                    directory.remove(name);
                }
            } else if (replacement != null) {
                if (previous != null && previous.mode() == FileMode.TREE) {
                    throw new IOException("Git path is a directory: " + path);
                }
                directory.put(name, replacement);
            } else if (remove) {
                if (previous != null && previous.mode() == FileMode.TREE) {
                    throw new IOException("Git path is a directory: " + path);
                }
                directory.remove(name);
            }
        }
        if (directory.isEmpty() && !prefix.isEmpty()) {
            return Optional.empty();
        }
        List<TreeEntry> sorted = new ArrayList<>(directory.values());
        sorted.sort((left, right) -> Arrays.compareUnsigned(treeSortName(left), treeSortName(right)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (TreeEntry entry : sorted) {
            writeTreeEntry(output, entry.mode(), entry.name(), entry.objectId());
        }
        return Optional.of(writeObject(GitObjectType.TREE, output.toByteArray()));
    }

    private Map<String, TreeEntry> readTree(ObjectId treeId) throws IOException, GitOperationException {
        Map<String, TreeEntry> directory = new HashMap<>();
        if (treeId == null) {
            return directory;
        }
        LooseObject tree = readObject(treeId);
        if (tree.type() != GitObjectType.TREE) {
            throw new GitOperationException("Tree target is not a tree: " + treeId);
        }
        byte[] data = tree.data();
        for (int offset = 0; offset < data.length;) {
            ParsedTreeEntry parsed = parseTreeEntry(treeId, data, offset);
            directory.put(parsed.entry().name(), parsed.entry());
            offset = parsed.nextOffset();
        }
        return directory;
    }

    private static byte[] treeSortName(TreeEntry entry) {
        String name = entry.name() + (entry.mode() == FileMode.TREE ? "/" : "");
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId writeCommit(
            ObjectId treeId,
            ObjectId parent,
            VcChangeDescription metadata) throws IOException {
        VcChangeAuthor commitAuthor = metadata.author();
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
                .append(commitMessage(metadata.message()))
                .append('\n');
        return writeObject(
                GitObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private LooseObject readObject(ObjectId objectId) throws IOException, GitOperationException {
        return readObject(objectId, (type, size, base, input) ->
                new LooseObject(objectId, type, input.newInputStream().readAllBytes()));
    }

    private <T> T readObject(ObjectId objectId, GitObjectRead<T> consumer)
            throws IOException, GitOperationException {
        ResolvedGitObjectRead<T> reader = new ResolvedGitObjectRead<>(storage, index,
                Optional.of(packId), consumer);
        Optional<IndexedObject> pendingObject = index.findObject(packId, objectId);
        if (pendingObject.isPresent()) {
            return GitObjectRead.read(storage, pendingObject.orElseThrow(), reader);
        }
        return GitObjectRead.read(storage, index, objectId, reader)
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
